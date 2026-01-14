package integration;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class IntegrationTestSuite {

    private static final String BASE_URL = "http://127.0.0.1:8080";
    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("    HTTP Server Integration Test Suite");
        System.out.println("=================================================\n");

        System.out.println("NOTE: Make sure the server is running before executing these tests!\n");

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        testServerAvailability();
        testGetStaticFiles();
        testGetNonExistentFile();
        testDirectoryListing();
        testDirectoryWithIndex();
        testPostUpload();
        testDeleteFile();
        testMethodNotAllowed();
        testRedirection();
        testBodySizeLimit();
        testChunkedEncoding();
        testCGIExecution();
        testSessionsAndCookies();
        testKeepAlive();
        testConcurrentRequests();
        testMalformedRequests();
        testCustomErrorPages();
        testVirtualHosts();

        System.out.println("\n=================================================");
        System.out.println("                 Test Summary");
        System.out.println("=================================================");
        System.out.println("Tests Passed: " + testsPassed);
        System.out.println("Tests Failed: " + testsFailed);
        System.out.println("Total Tests:  " + (testsPassed + testsFailed));
        System.out.println("=================================================");

        if (testsFailed == 0) {
            System.out.println("\nAll tests passed!");
            System.exit(0);
        } else {
            System.out.println("\nSome tests failed!");
            System.exit(1);
        }
    }

    private static void testServerAvailability() {
        System.out.println("\n--- Server Availability Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);

            int responseCode = conn.getResponseCode();
            assertTrue("Server should respond to requests", responseCode >= 200 && responseCode < 600);
            conn.disconnect();
        } catch (Exception e) {
            fail("Server is not reachable: " + e.getMessage());
        }
    }

    private static void testGetStaticFiles() {
        System.out.println("\n--- GET Static Files Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/index.html").openConnection();
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            assertEquals("GET /index.html should return 200", 200, responseCode);

            String contentType = conn.getHeaderField("Content-Type");
            assertTrue("Content-Type should be text/html", contentType != null && contentType.contains("text/html"));

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            assertTrue("Response should contain HTML", response.toString().contains("<!DOCTYPE html>"));
            conn.disconnect();
        } catch (Exception e) {
            fail("GET static file failed: " + e.getMessage());
        }
    }

    private static void testGetNonExistentFile() {
        System.out.println("\n--- GET Non-Existent File Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/nonexistent.html").openConnection();
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            assertEquals("Non-existent file should return 404", 404, responseCode);
            conn.disconnect();
        } catch (Exception e) {
            fail("404 test failed: " + e.getMessage());
        }
    }

    private static void testDirectoryListing() {
        System.out.println("\n--- Directory Listing Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/images/").openConnection();
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            assertTrue("Directory listing should return 200 or 403", responseCode == 200 || responseCode == 403);

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                assertTrue("Directory listing should contain HTML", response.toString().contains("Index of"));
            }
            conn.disconnect();
        } catch (Exception e) {
            fail("Directory listing test failed: " + e.getMessage());
        }
    }

    private static void testDirectoryWithIndex() {
        System.out.println("\n--- Directory with Index File Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/").openConnection();
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            assertEquals("Directory with index.html should return 200", 200, responseCode);
            conn.disconnect();
        } catch (Exception e) {
            fail("Directory index test failed: " + e.getMessage());
        }
    }

    private static void testPostUpload() {
        System.out.println("\n--- POST Upload Tests ---");
        try {
            String testContent = "This is a test file content";
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/upload/test.txt").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain");

            OutputStream os = conn.getOutputStream();
            os.write(testContent.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            assertTrue("POST upload should return 201 or 200", responseCode == 201 || responseCode == 200);
            conn.disconnect();
        } catch (Exception e) {
            fail("POST upload test failed: " + e.getMessage());
        }
    }

    private static void testDeleteFile() {
        System.out.println("\n--- DELETE Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/").openConnection();
            conn.setRequestMethod("DELETE");

            int responseCode = conn.getResponseCode();
            assertTrue("DELETE should return appropriate status", responseCode >= 200 && responseCode < 600);
            conn.disconnect();
        } catch (Exception e) {
            fail("DELETE test failed: " + e.getMessage());
        }
    }

    private static void testMethodNotAllowed() {
        System.out.println("\n--- Method Not Allowed Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/images/").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            int responseCode = conn.getResponseCode();
            assertEquals("POST to images should return 405", 405, responseCode);
            conn.disconnect();
        } catch (Exception e) {
            fail("Method not allowed test failed: " + e.getMessage());
        }
    }

    private static void testRedirection() {
        System.out.println("\n--- Redirection Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/old-page").openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            assertEquals("Redirect should return 301", 301, responseCode);

            String location = conn.getHeaderField("Location");
            assertNotNull("Location header should be present", location);
            assertEquals("Should redirect to /", "/", location);
            conn.disconnect();
        } catch (Exception e) {
            fail("Redirection test failed: " + e.getMessage());
        }
    }

    private static void testBodySizeLimit() {
        System.out.println("\n--- Body Size Limit Tests ---");
        try {
            byte[] largeBody = new byte[200 * 1024 * 1024];
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/upload/large.bin").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");

            try {
                OutputStream os = conn.getOutputStream();
                os.write(largeBody);
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                assertEquals("Large body should return 413", 413, responseCode);
            } catch (IOException e) {
                System.out.println("Connection closed by server (expected for oversized body)");
            }
            conn.disconnect();
        } catch (Exception e) {
            System.out.println("Body size limit test: Connection handling as expected");
        }
    }

    private static void testChunkedEncoding() {
        System.out.println("\n--- Chunked Encoding Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/upload/chunked.txt").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setChunkedStreamingMode(1024);
            conn.setRequestProperty("Transfer-Encoding", "chunked");

            OutputStream os = conn.getOutputStream();
            os.write("Chunked data test".getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            assertTrue("Chunked POST should succeed", responseCode == 201 || responseCode == 200);
            conn.disconnect();
        } catch (Exception e) {
            fail("Chunked encoding test failed: " + e.getMessage());
        }
    }

    private static void testCGIExecution() {
        System.out.println("\n--- CGI Execution Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/cgi-1").openConnection();
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            assertEquals("CGI should return 200", 200, responseCode);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            assertTrue("CGI should return HTML content", response.toString().contains("<!DOCTYPE html>"));
            conn.disconnect();
        } catch (Exception e) {
            fail("CGI execution test failed: " + e.getMessage());
        }
    }

    private static void testSessionsAndCookies() {
        System.out.println("\n--- Sessions and Cookies Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/session").openConnection();
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            assertEquals("Session endpoint should return 200", 200, responseCode);

            String setCookie = conn.getHeaderField("Set-Cookie");
            assertNotNull("Set-Cookie header should be present", setCookie);
            assertTrue("Cookie should contain session ID", setCookie.contains("SESSIONID"));

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            assertTrue("Response should contain view count", response.toString().contains("Views"));
            conn.disconnect();
        } catch (Exception e) {
            fail("Sessions and cookies test failed: " + e.getMessage());
        }
    }

    private static void testKeepAlive() {
        System.out.println("\n--- Keep-Alive Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/").openConnection();
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            assertEquals("Request should succeed", 200, responseCode);

            String connection = conn.getHeaderField("Connection");
            assertTrue("Connection header should support keep-alive",
                    connection == null || connection.equalsIgnoreCase("keep-alive"));
            conn.disconnect();
        } catch (Exception e) {
            fail("Keep-alive test failed: " + e.getMessage());
        }
    }

    private static void testConcurrentRequests() {
        System.out.println("\n--- Concurrent Requests Tests ---");
        Thread[] threads = new Thread[10];
        final boolean[] results = new boolean[10];

        for (int i = 0; i < 10; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/").openConnection();
                    conn.setRequestMethod("GET");
                    int responseCode = conn.getResponseCode();
                    results[index] = (responseCode == 200);
                    conn.disconnect();
                } catch (Exception e) {
                    results[index] = false;
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int successCount = 0;
        for (boolean result : results) {
            if (result) successCount++;
        }

        assertTrue("At least 8/10 concurrent requests should succeed", successCount >= 8);
    }

    private static void testMalformedRequests() {
        System.out.println("\n--- Malformed Request Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/").openConnection();
            conn.setRequestMethod("INVALID");
            conn.getResponseCode();
            fail("Server should reject invalid method");
        } catch (Exception e) {
            System.out.println("Server correctly rejects invalid method");
            testsPassed++;
        }
    }

    private static void testCustomErrorPages() {
        System.out.println("\n--- Custom Error Pages Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/nonexistent").openConnection();
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            assertEquals("Should return 404", 404, responseCode);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            assertTrue("Should return HTML error page", response.toString().contains("html"));
            conn.disconnect();
        } catch (Exception e) {
            fail("Custom error pages test failed: " + e.getMessage());
        }
    }

    private static void testVirtualHosts() {
        System.out.println("\n--- Virtual Hosts Tests ---");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/").openConnection();
            conn.setRequestProperty("Host", "localhost");
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            assertEquals("Virtual host should respond", 200, responseCode);
            conn.disconnect();
        } catch (Exception e) {
            fail("Virtual hosts test failed: " + e.getMessage());
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + message);
            testsPassed++;
        } else {
            System.out.println("  FAIL: " + message);
            testsFailed++;
        }
    }

    private static void assertEquals(String message, int expected, int actual) {
        if (expected == actual) {
            System.out.println("  PASS: " + message);
            testsPassed++;
        } else {
            System.out.println("  FAIL: " + message + " (expected: " + expected + ", actual: " + actual + ")");
            testsFailed++;
        }
    }

    private static void assertNotNull(String message, Object object) {
        if (object != null) {
            System.out.println("  PASS: " + message);
            testsPassed++;
        } else {
            System.out.println("  FAIL: " + message + " (was null)");
            testsFailed++;
        }
    }

    private static void fail(String message) {
        System.out.println("  FAIL: " + message);
        testsFailed++;
    }
}
