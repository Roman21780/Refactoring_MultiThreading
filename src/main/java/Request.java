import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

class Request {
    private final String method;
    private final String path;
    private final Map<String, String> headers;
    private final InputStream body;
    private final Map<String, List<String>> queryParams; // Поддержка дублирующихся параметров
    private final List<NameValuePair> queryParamsList;

    public Request(String method, String path,
                   Map<String, String> headers,
                   InputStream body) {
        this.method = method;
        this.headers = headers;
        this.body = body;

        // Разделяем путь и параметры запроса
        String[] pathParts = path.split("\\?", 2);
        this.path = pathParts[0];
        if (pathParts.length > 1) {
            this.queryParamsList = URLEncodedUtils.parse(pathParts[1], StandardCharsets.UTF_8);
            this.queryParams = queryParamsList.stream()
                    .collect(Collectors.groupingBy(
                            NameValuePair::getName,
                            Collectors.mapping(NameValuePair::getValue, Collectors.toList())
                    ));
        } else {
            this.queryParamsList = Collections.emptyList();
            this.queryParams = Collections.emptyMap();
        }
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getHeader(String name) {
        return headers.get(name);
    }

    public InputStream getBody() {
        return body;
    }

    public String getQueryParam(String name) {
        List<String> values = queryParams.get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null; // Возвращаем первое значение
    }

    public Map<String, List<String>> getQueryParams() {
        return Map.copyOf(queryParams); // Неизменяемая коллекция
    }

    public List<NameValuePair> getQueryParamsList() {
        return List.copyOf(queryParamsList); // Неизменяемая коллекция
    }
}