import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Request {
    private final String method;
    private final String path;
    private final String rawPath;
    private final Map<String, String> headers;
    private final InputStream body;
    private Map<String, List<String>> queryParams;
    private List<NameValuePair> queryParamsList;

    // поля для параметров POST
    private Map<String, List<String>> postParams;
    private List<NameValuePair> postParamsList;
    private boolean postParamsParsed = false;
    private final byte[] bodyBytes;

    public Request(String method, String path, Map<String, String> headers, InputStream body) throws IOException {
        this.method = method;
        this.rawPath = path;

        // Разделяем путь и параметры запроса
        String[] pathParts = path.split("\\?", 2);
        this.path = pathParts[0];

        this.headers = Collections.unmodifiableMap(headers);
        this.bodyBytes = body != null ? body.readAllBytes() : new byte[0];
        this.body = body;

        // Лениво инициализируемые поля для параметров запроса
        this.queryParams = null;
        this.queryParamsList = null;

        // поля для параметров POST
        this.postParams = null;
        this.postParamsList = null;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getRawPath() {
        return rawPath;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

//    public InputStream getBody() {
//        return body;
//    }

    // проверяет, является ли запрос x-www-form-urlencoded
    public boolean isFormUrlEncoded() {
        String contentType = headers.get("content-type");
        return contentType != null && contentType.contains("x-www-form-urlencoded");
    }

    // парсит параметры POST, если они есть и еще не были распарсены
    private synchronized void parsePostParamsIfNeeded() {
        if (postParamsParsed) return;

        postParamsParsed = true;

        if (!("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
            postParamsList = Collections.emptyList();
            postParams = Collections.emptyMap();
            return;
        }

        if (!isFormUrlEncoded()) {
            postParamsList = Collections.emptyList();
            postParams = Collections.emptyMap();
            return;
        }

        try {
            // читаем тело запроса
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = body.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            byte[] bodyBytes = baos.toByteArray();

            // парсим параметры
            String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
            postParamsList = URLEncodedUtils.parse(bodyStr, StandardCharsets.UTF_8);

            // конвертируем в Map
            postParams = new HashMap<>();
            for (NameValuePair pair : postParamsList) {
                postParams.computeIfAbsent(pair.getName(), k -> new ArrayList<>())
                        .add(pair.getValue());
            }

            // делаем неизменяемыми
            postParams.replaceAll((k, v) -> Collections.unmodifiableList(v));
            postParams = Collections.unmodifiableMap(postParams);
            postParamsList = Collections.unmodifiableList(postParamsList);

        } catch (IOException e) {
            System.err.println("Error parsing POST parameters: " + e.getMessage());
            postParamsList = Collections.emptyList();
            postParams = Collections.emptyMap();
        }
    }

    // Метод для получения тела как InputStream (для совместимости)
    public InputStream getBody() {
        return new ByteArrayInputStream(bodyBytes);
    }

    // Метод для получения тела как строки
    public String getBodyAsString() {
        return new String(bodyBytes, StandardCharsets.UTF_8);
    }

    // Получение всех параметров POST в виде Map<String, List<String>>
    public Map<String, List<String>> getPostParams() {
        parsePostParamsIfNeeded();
        return postParams;
    }

    // Получение всех параметров запроса в виде Map<String, List<String>>
    public synchronized Map<String, List<String>> getQueryParams() {
        if (queryParams == null) {
            queryParams = new HashMap<>();
            List<NameValuePair> params = getQueryParamsList();

            for (NameValuePair pair : params) {
                queryParams.computeIfAbsent(pair.getName(), k -> new ArrayList<>())
                        .add(pair.getValue());
            }

            // Делаем неизменяемыми все внутренние списки
            queryParams.replaceAll((k, v) -> Collections.unmodifiableList(v));
            queryParams = Collections.unmodifiableMap(queryParams);
        }
        return queryParams;
    }

    // Получение списка всех параметров POST
    public List<NameValuePair> getPostParamsList() {
        parsePostParamsIfNeeded();
        return postParamsList;
    }

    // Получение списка всех параметров запроса
    public synchronized List<NameValuePair> getQueryParamsList() {
        if (queryParamsList == null) {
            try {
                // Извлечение query string из пути
                String query = "";
                if (rawPath.contains("?")) {
                    query = rawPath.substring(rawPath.indexOf('?') + 1);
                }

                if (!query.isEmpty()) {
                    queryParamsList = URLEncodedUtils.parse(query, StandardCharsets.UTF_8);
                } else {
                    queryParamsList = Collections.emptyList();
                }
            } catch (Exception e) {
                System.err.println("Error parsing query parameters: " + e.getMessage());
                queryParamsList = Collections.emptyList();
            }

            queryParamsList = Collections.unmodifiableList(queryParamsList);
        }
        return queryParamsList;
    }

    // Получение первого значения параметра POST по имени
    public String getPostParam(String name) {
        List<String> values = getPostParams().get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    // Получение первого значения параметра по имени
    public String getQueryParam(String name) {
        List<String> values = getQueryParams().get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    // Получение всех значений параметра POST по имени
    public List<String> getPostParamValues(String name) {
        return getPostParams().getOrDefault(name, Collections.emptyList());
    }

    // Получение всех значений параметра по имени
    public List<String> getQueryParamValues(String name) {
        return getQueryParams().getOrDefault(name, Collections.emptyList());
    }

    // Метод для проверки наличия параметра POST
    public boolean hasPostParam(String name) {
        return getPostParams().containsKey(name);
    }

    // Метод для проверки наличия параметра
    public boolean hasQueryParam(String name) {
        return getQueryParams().containsKey(name);
    }

    @Override
    public String toString() {
        return method + " " + rawPath;
    }
}