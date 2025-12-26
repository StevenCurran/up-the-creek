package org.stac;


import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

public class PadelScraper {

    private static final String EMAIL = System.getenv("EMAIL");
    private static final String PASSWORD = System.getenv("PASSWORD");
    private static final String TOPIC = System.getenv("PUB_TOPIC");
    private static final String TENANT_ID = "0e339a49-7fc6-49b0-b4b7-44165dc0a8d7";

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    // Maps Resource ID to a readable Type (Singles/Doubles)
    private final Map<String, String> courtTypeMap = new HashMap<>();

    private String accessToken;
    private String refreshToken;

    public PadelScraper() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    static void main() throws Exception {
        var scraper = new PadelScraper();
        System.out.println("🚀 Starting Padel Scraper for Padel54");

        // 1. Log in and build the court map first
        scraper.login();
        scraper.fetchCourtMetadata();

        var fullReport = new StringBuilder();

        var today = LocalDate.now();
        String[] dates = {
                today.toString(),
                today.plusDays(1).toString(),
                today.plusDays(2).toString()
        };

        for (var date : dates) {
            var reportForDate = scraper.getAvailabilityReport(date);
            if (!reportForDate.isEmpty()) {
                fullReport.append("📅 **Date: ").append(date).append("**\n")
                        .append(reportForDate).append("\n");
            }
        }

        if (fullReport.length() > 0) {
            scraper.sendPushNotification(fullReport.toString());
            System.out.println(fullReport.toString());
            System.out.println("✅ Report sent to ntfy.");
        } else {
            System.out.println("📭 No availability found.");
        }
    }

    /**
     * Calls the Tenant endpoint to map Resource IDs to Singles/Doubles
     */
    public void fetchCourtMetadata() throws Exception {
        System.out.println("🔍 Fetching court metadata...");
        String url = "https://api.playtomic.io/v1/tenants/" + TENANT_ID;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonNode root = mapper.readTree(response.body());
            JsonNode resources = root.get("resources");

            for (JsonNode res : resources) {
                String id = res.get("resource_id").toString();
                // Check properties -> resource_size
                String size = res.path("properties").path("resource_size").asText("double");

                String type = size.equalsIgnoreCase("single") ? "Singles" : "Doubles";
                courtTypeMap.put(id, type);
            }
            System.out.println("✅ Mapped " + courtTypeMap.size() + " courts.");
        } else {
            System.err.println("❌ Failed to fetch metadata: " + response.statusCode());
        }
    }

    public String getAvailabilityReport(String date) throws Exception {
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
            return getAvailabilityReport(date);
        } else if (response.statusCode() == 200) {
            return processJsonAvailability(response.body());
        }
        return "";
    }

    private String processJsonAvailability(String jsonResponse) throws Exception {
        JsonNode root = mapper.readTree(jsonResponse);
        List<SlotInfo> doubleSlots = new ArrayList<>();
        List<SlotInfo> singleSlots = new ArrayList<>();

        for (JsonNode resource : root) {
            String resourceId = resource.get("resource_id").toString();
            String type = courtTypeMap.getOrDefault(resourceId, "Doubles");

            // --- IGNORE SINGLES HERE ---
            if ("Singles".equals(type)) {
                continue;
            }

            JsonNode slots = resource.get("slots");
            if (slots != null && slots.isArray()) {
                for (JsonNode slot : slots) {
                    SlotInfo info = new SlotInfo(slot);
                    if ("Singles".equals(type)) {
                        singleSlots.add(info);
                    } else {
                        doubleSlots.add(info);
                    }
                }
            }
        }

        // Sort by start time
        doubleSlots.sort(Comparator.comparing(s -> s.startTime));
        singleSlots.sort(Comparator.comparing(s -> s.startTime));

        StringBuilder output = new StringBuilder();
        if (!doubleSlots.isEmpty()) {
            output.append("👥 **Doubles**\n```\nTime  | Dur | Price\n");
            for (SlotInfo slot : doubleSlots) {
                output.append(slot.formatRow());
            }
            output.append("```\n");
        }
//        if (!singleSlots.isEmpty()) {
//            output.append("👤 **Singles**\n```\nTime  | Dur | Price\n");
//            for (SlotInfo slot : singleSlots) {
//                output.append(slot.formatRow());
//            }
//            output.append("```\n");
//        }
        return output.toString();
    }

    // Helper record to store slot information
    private record SlotInfo(String startTime, String duration, String price) {
        SlotInfo(JsonNode slot) {
            this(
                    slot.get("start_time").toString(),
                    slot.get("duration").toString() + "m",
                    slot.get("price").toString().replace(" GBP", "£")
            );
        }

        String formatRow() {
            String time = startTime.substring(0, 5);
            return String.format("%-5s | %-3s | %s\n", time, duration, price);
        }
    }

    // --- Auth Logic ---

    public void login() throws Exception {
        String payload = String.format("{\"email\":\"%s\",\"password\":\"%s\"}", EMAIL, PASSWORD);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.playtomic.io/v3/auth/login"))
                .header("Content-Type", "application/json")
                .header("User-Agent", "iOS 26.2")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonNode node = mapper.readTree(response.body());
            this.accessToken = node.get("access_token").toString();
            this.refreshToken = node.get("refresh_token").toString();
            System.out.println("🔑 Login successful.");
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
            this.accessToken = mapper.readTree(response.body()).get("access_token").toString();
        } else {
            login();
        }
    }

    public void sendPushNotification(String message) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://ntfy.sh/" + TOPIC))
                    .header("Title", "Padel54 Availability")
                    .header("Priority", "4")
                    .header("Tags", "racquet,calendar")
                    .header("Markdown", "yes")
                    .POST(HttpRequest.BodyPublishers.ofString(message))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.err.println("❌ Notification failed: " + e.getMessage());
        }
    }
}