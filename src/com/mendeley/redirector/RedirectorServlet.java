package com.mendeley.redirector;

import static com.rosaloves.bitlyj.Bitly.expand;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.sf.jsr107cache.Cache;
import net.sf.jsr107cache.CacheException;
import net.sf.jsr107cache.CacheManager;

import com.google.appengine.repackaged.com.google.common.base.Splitter;
import com.google.appengine.repackaged.com.google.common.base.StringUtil;
import com.google.appengine.repackaged.org.json.JSONException;
import com.google.appengine.repackaged.org.json.JSONObject;
import com.rosaloves.bitlyj.Bitly;
import com.rosaloves.bitlyj.Bitly.Provider;
import com.rosaloves.bitlyj.Url;

@SuppressWarnings("serial")
public class RedirectorServlet extends HttpServlet {
	
	private static final String MIME_JSON = "application/json";
	private static final String MIME_TEXT = "text/plain";
	private static final String MIME_HTML = "text/html";

	private enum Format {
		redirect,
		json,
		qr;
	}
	
	private static final String HTTP = "http";
	private static final String IDENT_URL_LOOKUP = "/oapi/documents/details/";
	private static final String GOOGLE_IT_QR = "http://chart.apis.google.com/chart?cht=qr&chs=230x230&chl=";
	
	private String MENDELEY_CONUSMER_KEY;
	private String MENDELEY_API_HOST;
	private String BITLY_KEY;

	
	private static final Logger log = Logger.getLogger(RedirectorServlet.class.getName());
	
	private Cache cache;
	
	@Override
	public void init(ServletConfig config) throws ServletException {
		// Get a connection to the memcache service.
		try {
			cache = CacheManager.getInstance().getCacheFactory().createCache(Collections.emptyMap());
		} catch (CacheException e) {
			log.log(Level.WARNING, "Could not create cache store", e);
		}
		
		// Load keys from properties file.
		Properties p = new Properties();
		
		try {
			p.load(config.getServletContext().getResourceAsStream("/WEB-INF/redirector.properties"));
		} catch (FileNotFoundException e) {
			log.log(Level.WARNING, "Could not find redirector properties file.", e);
		} catch (IOException e) {
			log.log(Level.WARNING, "Erro whilst loading redirector properties file.", e);
		}
		
		MENDELEY_CONUSMER_KEY = p.getProperty("mendeley_oapi_key");
		MENDELEY_API_HOST = p.getProperty("mendeley_oapi_host");
		BITLY_KEY = p.getProperty("bitly_key");
	}
	
	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		String path = req.getPathInfo();
		
		// Check we've got a valid path, and strip the leading /
		if (path.length() > 1) {
			path = StringUtil.stripPrefix(path, "/");
		} else {
			// Invalid request as the path has nothing other than the leading /
			// This by default should not be hit as requests to / should return index.html as defined in web.xml
			resp.setStatus(HttpURLConnection.HTTP_BAD_REQUEST);
			resp.getWriter().println("You need to specify something in the path of the request.");
			return;
		}
		
		// Try and get path from the cache
		String redirectUrl = (String)cache.get(path);
		
		// Try research id lookup if not in cache
		if (redirectUrl == null) {
			redirectUrl = mendeleyIdentifierLookup(path);
		}
		
		// Try bit.ly lookup if not in cache or a research article id
		if (redirectUrl == null) {
			redirectUrl = bitlyHashLookup(path);
		}
		
		// Find the format to return the url as, defaulting to a redirect.		
		Format format = Format.redirect;
		String formatString = req.getParameter("format");
		if (formatString != null) {
			try {
				format = Format.valueOf(formatString);
			} catch (IllegalArgumentException e) {
				resp.setContentType(MIME_TEXT);
				resp.setStatus(HttpURLConnection.HTTP_BAD_REQUEST);
				resp.getWriter().println("The format you specified was not valid.");
				return;
			}
		}
		
		// Return the url in the format requested
		if (redirectUrl != null && !redirectUrl.isEmpty()) {
			switch (format) {
				case json: // Turn the url into a json object and write this out.
					JSONObject json = new JSONObject();
					try {
						json.put("url", redirectUrl);
					} catch (JSONException e) {
						resp.setStatus(HttpURLConnection.HTTP_INTERNAL_ERROR);
						resp.getWriter().println("There was an error producing the output json.");
					}
					resp.setContentType(MIME_JSON);
					resp.getWriter().print(json.toString());
					break;
					
				case redirect: // Push the url as a http redirect header.
					resp.sendRedirect(redirectUrl);
					break;
					
				case qr: // Encode the url into a QR code url with the Google charts API and redirect to this.
					String encodedUrl = URLEncoder.encode(redirectUrl, "UTF8");
					String qrUrl = GOOGLE_IT_QR + encodedUrl;
					resp.sendRedirect(qrUrl);
					break;
					
				default: // This should not be hit, but if we don't match any other format, return an error.
					resp.setContentType(MIME_TEXT);
					resp.setStatus(HttpURLConnection.HTTP_BAD_REQUEST);
					resp.getWriter().println("The format you specified was not valid.");
					break;
			}
			
			// Put the looked up value in the cache
			cache.put(path, redirectUrl);
		} else {
			// No url for the path given.
			resp.setStatus(HttpURLConnection.HTTP_NOT_FOUND);
			resp.setContentType(MIME_HTML);
			resp.getWriter().println("No url was found for the given request. Maybe try to <a href='http://google.com/?q="+path+"'>Google</a> for it... ");
		}
	}

	private String bitlyHashLookup(String path) {
		// Look up against the Mendeley bit.ly key.
		Provider bitly = Bitly.as("mendeley", BITLY_KEY);
		Url url = bitly.call(expand("http://mnd.ly/" + path));
		return url.getLongUrl();
	}

	private String mendeleyIdentifierLookup(String path) {
		// Parse the URL path
		Splitter s = Splitter.on("/").limit(2);
		List<String> parts = s.splitToList(path);
		
		// Too few parameters.
		if (parts.size() != 2) {
			return null;
		}
		
		// Parse parameters
		String type = parts.get(0);
		String id = parts.get(1);
		
		// TODO: Check the type is one we can process (Not necessarily needed?)
		// TODO: Add support for other identifiers such as UUID and User document Id?
		
		// Lookup the identifier on Mendeley OPAI
		// TODO: Extract this into a Mendeley Java client API
		HttpURLConnection con = createAPIConnection(type, id);
		
		if (con == null) {
			return null;
		} else {
			return parseResponse(con);
		}
	}

	private String parseResponse(HttpURLConnection con) {
		// Check the response
		try {
			switch  (con.getResponseCode()) {
				case HttpURLConnection.HTTP_OK:
					// Get JSON response and parse
					BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
					
					StringBuffer rawJson = new StringBuffer();
					for (String line = reader.readLine(); line != null; line = reader.readLine()) {
						rawJson.append(line);
					}
					
					// Parse the URL out of the JSON
					try {
						JSONObject json = new JSONObject(rawJson.toString());
						return (String)json.get("mendeley_url");
					} catch (JSONException e) {
						return null;
					}
					
				case HttpURLConnection.HTTP_NOT_FOUND:
					return null;
					
				default:
					// Some form of error...
					// Log
					// return null
					return null;
			}
		} catch (IOException e) {
			return null;
		}
	}

	private HttpURLConnection createAPIConnection(String type, String id) {
		HttpURLConnection con;
		try {
			id = URLEncoder.encode(id.replace("/", "%2F"), "UTF8"); // Double up slashes to get around the Apache issues
			URL request = new URL(HTTP, MENDELEY_API_HOST, IDENT_URL_LOOKUP+id+"?type="+type+"&consumer_key="+MENDELEY_CONUSMER_KEY);
			con = (HttpURLConnection)request.openConnection();
			con.connect();
		} catch (IOException e) {
			// Log errors
			return null;
		}
		return con;
	}
}