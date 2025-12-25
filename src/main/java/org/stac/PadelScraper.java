package org.stac;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PadelScraper {

    private static final String EMAIL = System.getenv("EMAIL");
    private static final String PASSWORD = System.getenv("PASSWORD");
    private static final String TOPIC = System.getenv("PUB_TOPIC");
    private static final String TENANT_ID = "0e339a49-7fc6-49b0-b4b7-44165dc0a8d7";

    private final HttpClient httpClient;
    private String accessToken;
    private String refreshToken;

    public PadelScraper() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    public static void main(String[] args) throws Exception {
        var scraper = new PadelScraper();
        System.out.println("🚀 Starting Padel Scraper for Padel54 Moira");
        System.out.println("Pushing to ntfy.sh/" + TOPIC);

        StringBuilder fullReport = new StringBuilder();
        String[] dates = {"2025-12-26", "2025-12-27", "2025-12-28"};

        for (String date : dates) {
            String tableRows = scraper.getAvailability(date);
            if (!tableRows.isEmpty()) {
                fullReport.append("📅 **Date: ").append(date).append("**\n");
                fullReport.append("```\n");
                fullReport.append("Time  | Dur | Price\n");
                fullReport.append("------|-----|------\n");
                fullReport.append(tableRows);
                fullReport.append("```\n\n");
            }
        }

        if (fullReport.length() > 0) {
            scraper.sendPushNotification(fullReport.toString());
        } else {
            System.out.println("📭 No availability found for the requested dates.");
        }
    }

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
            throw new RuntimeException("Login failed: " + response.statusCode());
        }
    }

    public void refresh() throws Exception {
        if (refreshToken == null) {
            login();
            return;
        }
        String payload = "{\"refresh_token\":\"" + refreshToken + "\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.playtomic.io/v3/auth/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            this.accessToken = getValueFromJson(response.body(), "access_token");
            System.out.println("🔄 Token Refreshed.");
        } else {
            login();
        }
    }

    public String getAvailability(String date) throws Exception {
        if (accessToken == null) login();

        String url = String.format("https://api.playtomic.io/v1/availability?sport_id=PADEL" +
                "&start_max=%sT23:59:59&start_min=%sT00:00:00" +
                "&tenant_id=%s&user_id=me", date, date, TENANT_ID);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("User-Agent", "iOS 26.2")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401) {
            refresh();
            return getAvailability(date);
        } else if (response.statusCode() == 200) {
            return parseSlotsForTable(response.body());
        }
        return "";
    }

    private String parseSlotsForTable(String jsonResponse) {
        StringBuilder rows = new StringBuilder();
        Pattern slotPattern = Pattern.compile("\\{\"start_time\":\"(.*?)\",\"duration\":(\\d+),\"price\":\"(.*?)\"\\}");
        Matcher matcher = slotPattern.matcher(jsonResponse);

        boolean found = false;
        while (matcher.find()) {
            String time = matcher.group(1).substring(0, 5);
            String dur = matcher.group(2) + "m";
            String price = matcher.group(3).replace(" GBP", "£");

            // Alignment: %-5s (5 chars left aligned), %-3s (3 chars left aligned)
            rows.append(String.format("%-5s | %-3s | %s\n", time, dur, price));
            found = true;
        }
        return found ? rows.toString() : "";
    }

    public void sendPushNotification(String message) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://ntfy.sh/" + TOPIC))
                    .header("Title", "Padel Availability Found")
                    .header("Priority", "4")
                    .header("Tags", "racquet,calendar")
                    .header("Markdown", "yes") // Enables the monospaced table view
                    .POST(HttpRequest.BodyPublishers.ofString(message))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("🚀 Notification Status: " + response.statusCode());

        } catch (Exception e) {
            System.err.println("❌ Failed to send notification: " + e.getMessage());
        }
    }

    private String getValueFromJson(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\":\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}