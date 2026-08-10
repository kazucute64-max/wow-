package com.ourlauncher;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Real Microsoft account login for Minecraft, using the OAuth2 "device code"
 * flow — the same mechanism the official launcher, PojavLauncher, and every
 * legitimate third-party launcher use. This requires the person to actually
 * own Minecraft and log into their real Microsoft account; it does not
 * bypass or work around ownership in any way.
 *
 * The chain, in order:
 *   1. Request a device code from Microsoft's identity platform.
 *   2. Show the person a short code + a URL to visit on any device to
 *      approve the login.
 *   3. Poll Microsoft until they've approved it, getting back a Microsoft
 *      access token.
 *   4. Exchange that for an Xbox Live token.
 *   5. Exchange the Xbox Live token for an XSTS token (this is also where
 *      "does this account own Minecraft" effectively gets enforced later).
 *   6. Exchange the XSTS token for a Minecraft Services access token.
 *   7. Use that token to fetch the account's real Minecraft profile
 *      (username + UUID).
 */
public class MicrosoftAuth {

    // TODO: replace with your own Azure AD app's client ID.
    // Register one for free at https://portal.azure.com -> "App registrations"
    // -> "New registration". Requirements for this flow specifically:
    //   - Supported account types: "Personal Microsoft accounts only"
    //     (or the "...and personal Microsoft accounts" option)
    //   - Under Authentication: enable "Allow public client flows" = Yes
    //     (this is what makes the device code flow work without a client secret)
    // No special API permissions need to be added — Xbox Live/Minecraft
    // services are outside the standard Microsoft Graph permission list.
    public static final String CLIENT_ID = "PUT-YOUR-AZURE-APP-CLIENT-ID-HERE";

    private static final String DEVICE_CODE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_AUTH_URL =
            "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL =
            "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile";

    public interface LoginListener {
        /** Show this code + URL to the person — they approve the login on any device/browser. */
        void onDeviceCodeReceived(String userCode, String verificationUri, int expiresInSeconds);
        /** Called repeatedly while waiting for the person to approve in their browser. */
        void onWaitingForApproval();
        void onSuccess(MinecraftProfile profile, String minecraftAccessToken);
        void onError(Exception e);
    }

    public static void login(LoginListener listener) {
        try {
            DeviceCodeInfo deviceCode = requestDeviceCode();
            listener.onDeviceCodeReceived(deviceCode.userCode, deviceCode.verificationUri, deviceCode.expiresIn);

            String msAccessToken = pollForMicrosoftToken(deviceCode, listener);

            String xblToken = exchangeMicrosoftForXbl(msAccessToken);
            XstsResult xsts = exchangeXblForXsts(xblToken);
            String mcAccessToken = exchangeXstsForMinecraft(xsts.userhash, xsts.token);
            MinecraftProfile profile = fetchProfile(mcAccessToken);

            listener.onSuccess(profile, mcAccessToken);
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    private static DeviceCodeInfo requestDeviceCode() throws IOException {
        String body = "client_id=" + urlEncode(CLIENT_ID) +
                "&scope=" + urlEncode("XboxLive.signin offline_access");
        String response = httpPostForm(DEVICE_CODE_URL, body);
        try {
            JSONObject json = new JSONObject(response);
            DeviceCodeInfo info = new DeviceCodeInfo();
            info.deviceCode = json.getString("device_code");
            info.userCode = json.getString("user_code");
            info.verificationUri = json.getString("verification_uri");
            info.expiresIn = json.getInt("expires_in");
            info.interval = json.optInt("interval", 5);
            return info;
        } catch (Exception e) {
            throw new IOException("Failed to parse device code response: " + response, e);
        }
    }

    private static String pollForMicrosoftToken(DeviceCodeInfo deviceCode, LoginListener listener) throws IOException {
        long deadline = System.currentTimeMillis() + deviceCode.expiresIn * 1000L;
        int intervalMs = Math.max(deviceCode.interval, 5) * 1000;

        while (System.currentTimeMillis() < deadline) {
            listener.onWaitingForApproval();
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ie) {
                throw new IOException("Login cancelled", ie);
            }

            String body = "grant_type=" + urlEncode("urn:ietf:params:oauth:grant-type:device_code") +
                    "&client_id=" + urlEncode(CLIENT_ID) +
                    "&device_code=" + urlEncode(deviceCode.deviceCode);

            String response = httpPostFormAllowErrors(TOKEN_URL, body);
            try {
                JSONObject json = new JSONObject(response);
                if (json.has("access_token")) {
                    return json.getString("access_token");
                }
                String error = json.optString("error", "");
                switch (error) {
                    case "authorization_pending":
                        continue; // keep polling, person hasn't approved yet
                    case "slow_down":
                        intervalMs += 5000; // Microsoft asked us to back off
                        continue;
                    case "expired_token":
                        throw new IOException("Device code expired — the login attempt timed out. Try again.");
                    case "authorization_declined":
                        throw new IOException("Login was declined.");
                    default:
                        throw new IOException("Microsoft login error: " + response);
                }
            } catch (org.json.JSONException je) {
                throw new IOException("Unexpected response while polling for login: " + response, je);
            }
        }
        throw new IOException("Device code expired before approval was completed.");
    }

    private static String exchangeMicrosoftForXbl(String msAccessToken) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            JSONObject properties = new JSONObject();
            properties.put("AuthMethod", "RPS");
            properties.put("SiteName", "user.auth.xboxlive.com");
            properties.put("RpsTicket", "d=" + msAccessToken);
            payload.put("Properties", properties);
            payload.put("RelyingParty", "http://auth.xboxlive.com");
            payload.put("TokenType", "JWT");
        } catch (Exception e) {
            throw new IOException(e);
        }

        String response = httpPostJson(XBL_AUTH_URL, payload.toString());
        try {
            return new JSONObject(response).getString("Token");
        } catch (Exception e) {
            throw new IOException("Failed to parse Xbox Live auth response: " + response, e);
        }
    }

    private static XstsResult exchangeXblForXsts(String xblToken) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            JSONObject properties = new JSONObject();
            properties.put("SandboxId", "RETAIL");
            properties.put("UserTokens", new org.json.JSONArray().put(xblToken));
            payload.put("Properties", properties);
            payload.put("RelyingParty", "rp://api.minecraftservices.com/");
            payload.put("TokenType", "JWT");
        } catch (Exception e) {
            throw new IOException(e);
        }

        String response = httpPostJson(XSTS_AUTH_URL, payload.toString());
        try {
            JSONObject json = new JSONObject(response);
            if (json.has("XErr")) {
                long xErr = json.getLong("XErr");
                if (xErr == 2148916233L) {
                    throw new IOException("This Microsoft account has no Xbox Live profile. " +
                            "Sign into xbox.com once with it first, then try again.");
                } else if (xErr == 2148916238L) {
                    throw new IOException("This account is a child account and needs to be " +
                            "added to a Microsoft Family group before it can be used.");
                }
                throw new IOException("Xbox Live rejected this account (XErr " + xErr + ").");
            }
            XstsResult result = new XstsResult();
            result.token = json.getString("Token");
            result.userhash = json.getJSONObject("DisplayClaims")
                    .getJSONArray("xui").getJSONObject(0).getString("uhs");
            return result;
        } catch (org.json.JSONException je) {
            throw new IOException("Failed to parse XSTS response: " + response, je);
        }
    }

    private static String exchangeXstsForMinecraft(String userhash, String xstsToken) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("identityToken", "XBL3.0 x=" + userhash + ";" + xstsToken);
        } catch (Exception e) {
            throw new IOException(e);
        }

        String response = httpPostJson(MC_LOGIN_URL, payload.toString());
        try {
            return new JSONObject(response).getString("access_token");
        } catch (Exception e) {
            throw new IOException("Failed to parse Minecraft services login response: " + response, e);
        }
    }

    /** Confirms game ownership implicitly: this call 404s/errors for accounts that don't own Minecraft. */
    private static MinecraftProfile fetchProfile(String mcAccessToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(MC_PROFILE_URL).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + mcAccessToken);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        try {
            int code = conn.getResponseCode();
            String response = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code == 404 || code == 401) {
                throw new IOException("This Microsoft account doesn't appear to own Minecraft: Java Edition.");
            }
            JSONObject json = new JSONObject(response);
            MinecraftProfile profile = new MinecraftProfile();
            profile.id = json.getString("id");
            profile.name = json.getString("name");
            return profile;
        } catch (org.json.JSONException je) {
            throw new IOException("Failed to parse Minecraft profile response", je);
        } finally {
            conn.disconnect();
        }
    }

    // ---- small HTTP helpers ----

    private static String httpPostForm(String url, String formBody) throws IOException {
        return httpPost(url, formBody, "application/x-www-form-urlencoded", false);
    }

    /** Like httpPostForm, but returns the body even on 4xx responses (needed to read "authorization_pending" etc). */
    private static String httpPostFormAllowErrors(String url, String formBody) throws IOException {
        return httpPost(url, formBody, "application/x-www-form-urlencoded", true);
    }

    private static String httpPostJson(String url, String jsonBody) throws IOException {
        return httpPost(url, jsonBody, "application/json", false);
    }

    private static String httpPost(String url, String body, String contentType, boolean allowErrorBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", contentType);
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        try {
            if (code >= 400) {
                if (allowErrorBody) {
                    return readStream(conn.getErrorStream());
                }
                throw new IOException("HTTP " + code + " from " + url + ": " + readStream(conn.getErrorStream()));
            }
            return readStream(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String readStream(java.io.InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ---- small data holders ----

    private static class DeviceCodeInfo {
        String deviceCode;
        String userCode;
        String verificationUri;
        int expiresIn;
        int interval;
    }

    private static class XstsResult {
        String token;
        String userhash;
    }

    public static class MinecraftProfile {
        public String id;   // account UUID (no dashes)
        public String name; // in-game username
    }
}
