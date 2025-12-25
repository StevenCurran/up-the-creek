package org.stac;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaytomicAppScraper2025 {

    private static final String EMAIL = "boarder.kite@googlemail.com";
    private static final String PASSWORD = "Flatness-Margarine-Rural-Reanalyze5";
    private static final String TENANT_ID = "0e339a49-7fc6-49b0-b4b7-44165dc0a8d7";

    private final HttpClient httpClient;
    private String accessToken;
    private String refreshToken;

    public PlaytomicAppScraper2025() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    public static void main(String[] args) throws Exception {
        var scraper = new PlaytomicAppScraper2025();
        scraper.getAvailability("2025-12-26");
        scraper.getAvailability("2025-12-27");
        scraper.getAvailability("2025-12-28");
    }

    /**
     * Authenticates using the v3 Login endpoint
     */
    public void login() throws Exception {
        String payload = String.format("{\"email\":\"%s\",\"password\":\"%s\"}", EMAIL, PASSWORD);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.playtomic.io/v3/auth/login"))
                .header("Content-Type", "application/json")
                .header("User-Agent", "iOS 26.2")
                .header("X-Requested-With", "com.playtomic.app 6.55.1")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            this.accessToken = getValueFromJson(response.body(), "access_token");
            this.refreshToken = getValueFromJson(response.body(), "refresh_token");
            System.out.println("✅ Login Successful.");
        } else {
            throw new RuntimeException("Login failed: " + response.statusCode() + " " + response.body());
        }
    }

    /**
     * Refreshes the session using the Refresh Token
     */
    public void refresh() throws Exception {
        if (refreshToken == null) {
            login();
            return;
        }

        String payload = "{\"refresh_token\":\"" + refreshToken + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.playtomic.io/v3/auth/token"))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Playtomic/31542 CFNetwork/3860.300.31 Darwin/25.2.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            this.accessToken = getValueFromJson(response.body(), "access_token");
            System.out.println("🔄 Token Refreshed.");
        } else {
            login(); // If refresh fails, do a full login
        }
    }

    /**
     * Fetches availability for a specific date (YYYY-MM-DD)
     */
    public void getAvailability(String date) throws Exception {
        if (accessToken == null) login();

        String url = String.format("https://api.playtomic.io/v1/availability?sport_id=PADEL" +
                "&start_max=%sT23:59:59&start_min=%sT00:00:00" +
                "&tenant_id=%s&user_id=me", date, date, TENANT_ID);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("User-Agent", "iOS 26.2")
                .header("X-Requested-With", "com.playtomic.app 6.55.1")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401) {
            refresh();
            getAvailability(date); // Retry once after refresh
        } else if (response.statusCode() == 200) {
            System.out.println("🎾 Found Availability for " + date);
            formatAndPrintAvailability(response.body());

        } else {
            System.out.println("❌ Search Failed: " + response.statusCode());
        }
    }

    public String getCourtType(String resourceId) throws Exception {
        String url = String.format("https://api.playtomic.io/v1/tenants/%s/resources/%s", TENANT_ID, resourceId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("User-Agent", "iOS 26.2")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            String body = response.body();
            // Look for the "size" property: "2" for Singles, "4" for Doubles
            if (body.contains("\"size\":2")) return "Singles";
            if (body.contains("\"size\":4")) return "Doubles";

            // Alternative: Some clubs put it in the "name"
            return getValueFromJson(body, "name");
        }
        return "Unknown";
    }

    public void formatAndPrintAvailability(String jsonResponse) {
        System.out.println("\n--- PADEL54 MOIRA AVAILABILITY ---");

        // Split by resource_id to isolate each court
        String[] courts = jsonResponse.split("\\{\"resource_id\":");

        for (String court : courts) {
            if (court.trim().isEmpty() || !court.contains("slots")) continue;

            // Extract Court ID (shorthand)
            String courtId = court.substring(1, 6) + "...";
            System.out.println("\nCourt [" + courtId + "]:");

            // Use regex to find all slot patterns: {"start_time":"HH:mm:ss","duration":X,"price":"Y"}
            Pattern slotPattern = Pattern.compile("\\{\"start_time\":\"(.*?)\",\"duration\":(\\d+),\"price\":\"(.*?)\"\\}");
            Matcher matcher = slotPattern.matcher(court);

            boolean found = false;
            while (matcher.find()) {
                String startTime = matcher.group(1).substring(0, 5); // Just HH:mm
                String duration = matcher.group(2);
                String price = matcher.group(3);

                System.out.printf("  🕒 %s (%s min) - %s\n", startTime, duration, price);
                found = true;
            }

            if (!found) System.out.println("  (No slots available)");
        }
    }

    private String getValueFromJson(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\":\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}