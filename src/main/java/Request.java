import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

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

    public Request(String method, String path, Map<String, String> headers, InputStream body) {
        this.method = method;
        this.rawPath = path;

        // Разделяем путь и параметры запроса
        String[] pathParts = path.split("\\?", 2);
        this.path = pathParts[0];

        this.headers = Collections.unmodifiableMap(headers);
        this.body = body;

        // Лениво инициализируемые поля для параметров запроса
        this.queryParams = null;
        this.queryParamsList = null;
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

    public InputStream getBody() {
        return body;
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

    // Получение первого значения параметра по имени
    public String getQueryParam(String name) {
        List<String> values = getQueryParams().get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    // Получение всех значений параметра по имени
    public List<String> getQueryParamValues(String name) {
        return getQueryParams().getOrDefault(name, Collections.emptyList());
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