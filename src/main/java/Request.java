import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.util.Streams;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class Request {
    private final String method;
    private final String path;
    private final String rawPath;
    private final Map<String, String> headers;
    private final byte[] bodyBytes;

    // Для query параметров
    private Map<String, List<String>> queryParams;
    private List<NameValuePair> queryParamsList;

    // Для POST параметров
    private Map<String, List<String>> postParams;
    private List<NameValuePair> postParamsList;
    private boolean postParamsParsed = false;

    // Для multipart данных
    private List<Part> parts;
    private boolean multipartParsed = false;

    public Request(String method, String path, Map<String, String> headers, InputStream body) throws IOException {
        this.method = method;
        this.rawPath = path;
        this.path = path.split("\\?", 2)[0];
        this.headers = Collections.unmodifiableMap(headers);
        this.bodyBytes = body != null ? body.readAllBytes() : new byte[0];
    }

    // ========== Multipart обработка ==========

    public boolean isMultipart() {
        String contentType = headers.get("content-type");
        return contentType != null && contentType.startsWith("multipart/form-data");
    }

    private synchronized void parseMultipart() {
        if (multipartParsed) return;
        multipartParsed = true;

        if (!isMultipart()) {
            parts = Collections.emptyList();
            return;
        }

        try {
            String contentType = headers.get("content-type");
            String boundary = "--" + extractBoundary(contentType);

            parts = parseMultipartBody(new ByteArrayInputStream(bodyBytes), boundary);
        } catch (Exception e) {
            System.err.println("Multipart parse error: " + e.getMessage());
            parts = Collections.emptyList();
        }
    }

    private List<Part> parseMultipartBody(InputStream input, String boundary) throws IOException {
        List<Part> parsedParts = new ArrayList<>();
        byte[] boundaryBytes = boundary.getBytes(StandardCharsets.US_ASCII);
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains(boundary)) {
                Map<String, String> headers = readPartHeaders(reader);
                String fieldName = extractFieldName(headers);
                if (fieldName != null) {
                    ByteArrayOutputStream content = readPartContent(reader, boundary);
                    String fileName = extractFileName(headers);

                    parsedParts.add(new Part(
                            fieldName,
                            fileName,
                            headers.get("content-type"),
                            content.toByteArray(),
                            fileName != null
                    ));
                }
            }
        }

        return parsedParts;
    }

    private Map<String, String> readPartHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(
                        line.substring(0, colon).trim().toLowerCase(),
                        line.substring(colon + 1).trim()
                );
            }
        }
        return headers;
    }

    private ByteArrayOutputStream readPartContent(BufferedReader reader, String boundary) throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        String line;
        while ((line = reader.readLine()) != null && !line.contains(boundary)) {
            content.write(line.getBytes(StandardCharsets.UTF_8));
            content.write("\r\n".getBytes());
        }
        return content;
    }

    private String extractBoundary(String contentType) {
        String[] parts = contentType.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                return part.substring("boundary=".length()).trim();
            }
        }
        return null;
    }

    private boolean trySkipNextBoundary(InputStream input, byte[] boundary) throws IOException {
        int boundaryPos = 0;
        int b;

        while ((b = input.read()) != -1) {
            if (b == boundary[boundaryPos]) {
                boundaryPos++;
                if (boundaryPos == boundary.length) {
                    return true; // Нашли следующий boundary
                }
            } else {
                boundaryPos = 0;
            }
        }
        return false; // Достигнут конец потока
    }

    private void skipUntilBoundary(InputStream input, byte[] boundary) throws IOException {
        byte[] buffer = new byte[4096];
        int boundaryPos = 0;
        int bytesRead;

        while ((bytesRead = input.read(buffer)) != -1) {
            for (int i = 0; i < bytesRead; i++) {
                if (buffer[i] == boundary[boundaryPos]) {
                    boundaryPos++;
                    if (boundaryPos == boundary.length) {
                        return; // Нашли boundary
                    }
                } else {
                    boundaryPos = 0;
                }
            }
        }
        throw new IOException("Boundary not found");
    }

    private Map<String, String> readHeaders(InputStream input) throws IOException {
        Map<String, String> headers = new HashMap<>();
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int prev = -1;
        int curr;

        while ((curr = input.read()) != -1) {
            if (prev == '\r' && curr == '\n') {
                byte[] lineBytes = headerBuf.toByteArray();
                if (lineBytes.length == 0) {
                    break; // Конец заголовков
                }

                String line = new String(lineBytes, StandardCharsets.US_ASCII);
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase();
                    String value = line.substring(colon + 1).trim();
                    headers.put(name, value);
                }

                headerBuf.reset();
                prev = -1;
                continue;
            }

            if (prev != -1) {
                headerBuf.write(prev);
            }
            prev = curr;
        }

        return headers;
    }

    private String extractFieldName(Map<String, String> headers) {
        String disposition = headers.get("content-disposition");
        if (disposition == null) return null;

        String[] parts = disposition.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("name=")) {
                String name = part.substring("name=".length());
                if (name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                return name;
            }
        }
        return null;
    }

    private String extractFileName(Map<String, String> headers) {
        String disposition = headers.get("content-disposition");
        if (disposition == null) return null;

        String[] parts = disposition.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("filename=")) {
                String name = part.substring("filename=".length());
                if (name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                return name;
            }
        }
        return null;
    }

    private void readPartBody(InputStream input, byte[] boundary, OutputStream output) throws IOException {
        // Упрощенная реализация - в реальном коде нужно учитывать больше случаев
        byte[] buf = new byte[4096];
        int boundaryPos = 0;
        int b;

        while ((b = input.read()) != -1) {
            if (b == boundary[boundaryPos]) {
                boundaryPos++;
                if (boundaryPos == boundary.length) {
                    break; // Нашли boundary
                }
            } else {
                if (boundaryPos > 0) {
                    output.write(boundary, 0, boundaryPos);
                    boundaryPos = 0;
                }
                output.write(b);
            }
        }
    }

    public Part getPart(String name) {
        parseMultipart();
        return parts.stream()
                .filter(part -> part.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public List<Part> getParts() {
        parseMultipart();
        return parts;
    }

    public List<Part> getParts(String name) {
        parseMultipart();
        return parts.stream()
                .filter(part -> part.getName().equals(name))
                .collect(Collectors.toList());
    }

    // ========== Остальные методы ==========

    public InputStream getBody() {
        return new ByteArrayInputStream(bodyBytes);
    }

    public String getBodyAsString() {
        return new String(bodyBytes, StandardCharsets.UTF_8);
    }

    // Методы для работы с POST параметрами (x-www-form-urlencoded)
    public boolean isFormUrlEncoded() {
        String contentType = headers.get("content-type");
        return contentType != null && contentType.contains("x-www-form-urlencoded");
    }

    private synchronized void parsePostParamsIfNeeded() {
        if (postParamsParsed) return;
        postParamsParsed = true;

        if (!("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) || !isFormUrlEncoded()) {
            postParamsList = Collections.emptyList();
            postParams = Collections.emptyMap();
            return;
        }

        try {
            String bodyStr = getBodyAsString();
            postParamsList = URLEncodedUtils.parse(bodyStr, StandardCharsets.UTF_8);

            postParams = new HashMap<>();
            for (NameValuePair pair : postParamsList) {
                postParams.computeIfAbsent(pair.getName(), k -> new ArrayList<>())
                        .add(pair.getValue());
            }

            postParams.replaceAll((k, v) -> Collections.unmodifiableList(v));
            postParams = Collections.unmodifiableMap(postParams);
            postParamsList = Collections.unmodifiableList(postParamsList);
        } catch (Exception e) {
            System.err.println("Error parsing POST parameters: " + e.getMessage());
            postParamsList = Collections.emptyList();
            postParams = Collections.emptyMap();
        }
    }

    public Map<String, List<String>> getPostParams() {
        parsePostParamsIfNeeded();
        return postParams;
    }

    public List<NameValuePair> getPostParamsList() {
        parsePostParamsIfNeeded();
        return postParamsList;
    }

    public String getPostParam(String name) {
        List<String> values = getPostParams().get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    public List<String> getPostParamValues(String name) {
        return getPostParams().getOrDefault(name, Collections.emptyList());
    }

    public boolean hasPostParam(String name) {
        return getPostParams().containsKey(name);
    }

    // Методы для работы с query параметрами
    public synchronized Map<String, List<String>> getQueryParams() {
        if (queryParams == null) {
            queryParams = new HashMap<>();
            for (NameValuePair pair : getQueryParamsList()) {
                queryParams.computeIfAbsent(pair.getName(), k -> new ArrayList<>())
                        .add(pair.getValue());
            }
            queryParams.replaceAll((k, v) -> Collections.unmodifiableList(v));
            queryParams = Collections.unmodifiableMap(queryParams);
        }
        return queryParams;
    }

    public synchronized List<NameValuePair> getQueryParamsList() {
        if (queryParamsList == null) {
            try {
                String query = rawPath.contains("?") ? rawPath.substring(rawPath.indexOf('?') + 1) : "";
                queryParamsList = query.isEmpty() ?
                        Collections.emptyList() :
                        URLEncodedUtils.parse(query, StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.err.println("Error parsing query parameters: " + e.getMessage());
                queryParamsList = Collections.emptyList();
            }
            queryParamsList = Collections.unmodifiableList(queryParamsList);
        }
        return queryParamsList;
    }

    public String getQueryParam(String name) {
        List<String> values = getQueryParams().get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    public List<String> getQueryParamValues(String name) {
        return getQueryParams().getOrDefault(name, Collections.emptyList());
    }

    public boolean hasQueryParam(String name) {
        return getQueryParams().containsKey(name);
    }

    // Базовые геттеры
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getRawPath() { return rawPath; }
    public Map<String, String> getHeaders() { return headers; }

    @Override
    public String toString() {
        return method + " " + rawPath;
    }

    // Класс Part для работы с частями multipart запроса
    public static class Part {
        private final String name;
        private final String fileName;
        private final String contentType;
        private final byte[] content;
        private final boolean isFile;

        public Part(String name, String fileName, String contentType, byte[] content, boolean isFile) {
            this.name = name;
            this.fileName = fileName;
            this.contentType = contentType;
            this.content = content;
            this.isFile = isFile;
        }

        public String getName() { return name; }
        public String getFileName() { return fileName; }
        public String getContentType() { return contentType; }
        public boolean isFile() { return isFile; }
        public long getSize() { return content.length; }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        public String getString() {
            return new String(content, StandardCharsets.UTF_8);
        }

        public byte[] getBytes() {
            return content.clone();
        }
    }
}