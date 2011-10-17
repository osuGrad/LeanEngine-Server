package com.leanengine.server;

import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.appengine.api.urlfetch.URLFetchServiceFactory;
import com.leanengine.server.appengine.DatastoreUtils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class FacebookAuth {

    static private ObjectMapper mapper = new ObjectMapper();

    public static String getLoginUrlMobile(String serverName, String state) {
        String redirectUrl = serverName + "/login/facebook-auth.jsp";
        return "https://m.facebook.com/dialog/oauth?" +
                "client_id=" + LeanEngineSettings.getFacebookAppID() + "&" +
                "redirect_uri=" + redirectUrl + "&" +
                "scope=email&" +
                "state=" + state;
    }

    public static String getLoginUrlWeb(String serverName, String state) {
        String redirectUrl = serverName + "/login/facebook-auth.jsp";
        return "https://www.facebook.com/dialog/oauth?" +
                "client_id=" + LeanEngineSettings.getFacebookAppID() + "&" +
                "redirect_uri=" + redirectUrl + "&" +
                "scope=email&" +
                "state=" + state;
    }

    public static String getGraphAuthUrl(String redirectUrl, String code) {
        return "https://graph.facebook.com/oauth/access_token?" +
                "client_id=" + LeanEngineSettings.getFacebookAppID() + "&" +
                "redirect_uri=" + redirectUrl + "&" +
                "client_secret=" + LeanEngineSettings.getFacebookAppSecret() + "&" +
                "code=" + code;
    }

    public static JsonNode getUserData(String access_token) throws LeanException {
        String url = "https://graph.facebook.com/me?access_token=" + access_token;
        URLFetchService fetchService = URLFetchServiceFactory.getURLFetchService();
        HTTPResponse fetchResponse;
        try {
            fetchResponse = fetchService.fetch(new URL(url));
            if (fetchResponse.getResponseCode() == 200) {
                return mapper.readTree(new String(fetchResponse.getContent(), "UTF-8"));
            } else {
                //todo log this - Facebook Graph responded with error code
                // todo throw error
                throw new LeanException(LeanException.Error.FacebookAuthError);
            }
        } catch (MalformedURLException ex) {
            //todo This should not happen - log it
        } catch (JsonProcessingException ex) {
            throw new LeanException(LeanException.Error.FacebookAuthParseError, ex);
        } catch (IOException e) {
            throw new LeanException(LeanException.Error.FacebookAuthConnectError, e);
        }
        return null;
    }

    public static AuthToken graphAuthenticate(String currentUrl, String code) throws LeanException {

        try {
            URL facebookGraphUrl = new URL(FacebookAuth.getGraphAuthUrl(currentUrl, code));
            URLFetchService fetchService = URLFetchServiceFactory.getURLFetchService();
            HTTPResponse fetchResponse = fetchService.fetch(facebookGraphUrl);
            if (fetchResponse.getResponseCode() == 400) {
                // error: facebook server error replied with 400
                throw new LeanException(LeanException.Error.FacebookAuthResponseError);
            }
            String responseContent = new String(fetchResponse.getContent(), Charset.forName("UTF-8"));

            String fbAccessToken = null, expires = null;
            String[] splitResponse = responseContent.split("&");

            if (splitResponse.length != 2) {
                // error: facebook should return two arguments: access_token & expires
                throw new LeanException(LeanException.Error.FacebookAuthMissingParamError);
            }
            for (String split : splitResponse) {
                String[] parts = split.split("=");
                if (parts.length != 2) break;
                fbAccessToken = parts[0].equals("access_token") ? parts[1] : fbAccessToken;
                expires = parts[0].equals("expires") ? parts[1] : expires;
            }

            // check if we got required parameters
            if (fbAccessToken == null || expires == null) {
                //error: wrong parameters: facebook should return 'access_token' and 'expires' parameters
                throw new LeanException(LeanException.Error.FacebookAuthMissingParamError);
            }

            // All is good - check the user
            JsonNode userData = FacebookAuth.getUserData(fbAccessToken);
            String providerID = userData.get("id").getTextValue();

            if (providerID == null || providerID.length() == 0) {
                //Facebook returned user data, but email field is missing
                throw new LeanException(LeanException.Error.FacebookAuthMissingParamError);
            }

            LeanAccount account = DatastoreUtils.findAccountByProvider(providerID, "fb-oauth");
            if (account == null) {
                //todo this is one-to-one mapping between Account and User
                //change this in the future

                // account does not yet exist - create it
                account = parseAccountFB(userData);
                DatastoreUtils.saveAccount(account);
            }

            // create our own authentication token
            return AuthService.createAuthToken(account.id);

        } catch (MalformedURLException e) {
            throw new LeanException(LeanException.Error.FacebookAuthNoConnectionError, e);
        } catch (IOException e) {
            throw new LeanException(LeanException.Error.FacebookAuthNoConnectionError, e);
        }
    }


    /**
     * Creates LeanAccount form data returned by Facebook authentication service
     *
     * @param userData JSON data as provided by Facebook OAuth
     * @return
     */
    public static LeanAccount parseAccountFB(JsonNode userData) {

        Map<String, Object> props = new HashMap<String, Object>(userData.size());
        Iterator<String> fields = userData.getFieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            // field 'id' is not treated as part of provider properties
            if (field.equals("id")) continue;
            props.put(field, userData.get(field).getValueAsText());
        }

        return new LeanAccount(
                0,
                userData.get("name").getTextValue(),
                userData.get("id").getTextValue(),
                "fb-oauth",
                props);
    }
}
