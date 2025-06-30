package WebCloner;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebCloner {

    private final Set<String> visited = new HashSet<>();
    private final Deque<String> queue = new LinkedList<>();
    private final CloseableHttpClient httpClient;

    // Callbacks para comunicar con la GUI
    public interface LogCallback {
        void log(String message);
    }

    public interface ProgressCallback {
        void update(int current, int total);
    }

    private final LogCallback logCallback;
    private final ProgressCallback progressCallback;
    private int totalElementsToProcess = 0; // Para el progreso
    private int processedElements = 0;

    private static final Set<String> RESOURCE_EXTENSIONS = new HashSet<>();
    static {
        RESOURCE_EXTENSIONS.add(".css"); RESOURCE_EXTENSIONS.add(".js");
        RESOURCE_EXTENSIONS.add(".png"); RESOURCE_EXTENSIONS.add(".jpg");
        RESOURCE_EXTENSIONS.add(".jpeg"); RESOURCE_EXTENSIONS.add(".gif");
        RESOURCE_EXTENSIONS.add(".ico"); RESOURCE_EXTENSIONS.add(".svg");
        RESOURCE_EXTENSIONS.add(".woff"); RESOURCE_EXTENSIONS.add(".woff2");
        RESOURCE_EXTENSIONS.add(".ttf"); RESOURCE_EXTENSIONS.add(".eot");
        RESOURCE_EXTENSIONS.add(".pdf"); RESOURCE_EXTENSIONS.add(".doc");
        RESOURCE_EXTENSIONS.add(".docx"); RESOURCE_EXTENSIONS.add(".xls");
        RESOURCE_EXTENSIONS.add(".xlsx"); RESOURCE_EXTENSIONS.add(".zip");
        RESOURCE_EXTENSIONS.add(".mp3"); RESOURCE_EXTENSIONS.add(".mp4");
        RESOURCE_EXTENSIONS.add(".webm"); RESOURCE_EXTENSIONS.add(".ogg");
        RESOURCE_EXTENSIONS.add(".json"); RESOURCE_EXTENSIONS.add(".xml");
    }

    public WebCloner(LogCallback logCallback, ProgressCallback progressCallback) {
        this.logCallback = logCallback;
        this.progressCallback = progressCallback;
        this.httpClient = HttpClients.createDefault(); // Inicializa HttpClient
    }

    private void log(String message) {
        if (logCallback != null) {
            logCallback.log(message);
        }
    }

    private void updateProgress() {
        if (progressCallback != null) {
            progressCallback.update(processedElements, totalElementsToProcess);
        }
    }

    private String sanitizePath(String path) {
        // Elimina caracteres inválidos para nombres de archivo/ruta
        String sanitized = path.replaceAll("[\\\\:*?\"<>|]", "_");
        return sanitized.replaceAll("^/+|/+$", ""); // Elimina barras al inicio/fin
    }

    private void saveFile(String url, String localPath) throws IOException {
        File file = new File(localPath);
        file.getParentFile().mkdirs(); // Asegura que los directorios existan

        HttpGet request = new HttpGet(url);
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            if (response.getStatusLine().getStatusCode() == 200) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    response.getEntity().writeTo(fos);
                }
            } else {
                log(String.format("[ERROR] No se pudo descargar %s. Código: %d", url, response.getStatusLine().getStatusCode()));
            }
        } catch (Exception e) {
            log(String.format("[ERROR] No se pudo descargar: %s -> %s", url, e.getMessage()));
            throw new IOException("Error al descargar archivo", e);
        }
    }

    private String localResourcePath(String baseDir, String resourceUrl) {
        try {
            URL url = new URL(resourceUrl);
            String path = sanitizePath(url.getPath());

            if (path.isEmpty() || path.endsWith("/")) {
                path += "index.html";
            } else if (!path.contains(".")) { // Si no tiene extensión, asumir que es un directorio
                path += "/index.html";
            }

            return new File(baseDir, path).getAbsolutePath();
        } catch (Exception e) {
            log("[ERROR] Error al calcular ruta local para: " + resourceUrl + " -> " + e.getMessage());
            return null;
        }
    }

    private boolean isInternalLink(String url, String baseDomain) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            return host == null || host.equals(baseDomain);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isResourceUrl(String url) {
        String path = new File(url).getName();
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex > 0) {
            String extension = path.substring(dotIndex).toLowerCase();
            return RESOURCE_EXTENSIONS.contains(extension);
        }
        return false;
    }

    private boolean isHtmlPageUrl(String url) {
        // Asumimos que si no es un recurso con extensión conocida, es una página HTML
        return !isResourceUrl(url);
    }

    private void processHtml(String url, String htmlContent, String baseDir, String rootUrl) throws IOException {
        Document doc = Jsoup.parse(htmlContent, url); // url base para resolver enlaces relativos
        String currentLocalPath = localResourcePath(baseDir, url);

        String rootDomain = new URL(rootUrl).getHost();

        // Mapeo de tags a atributos
        java.util.Map<String, String> tagAttrMap = new java.util.HashMap<>();
        tagAttrMap.put("link", "href");
        tagAttrMap.put("script", "src");
        tagAttrMap.put("img", "src");
        tagAttrMap.put("a", "href");
        tagAttrMap.put("iframe", "src");

        for (java.util.Map.Entry<String, String> entry : tagAttrMap.entrySet()) {
            String tag = entry.getKey();
            String attr = entry.getValue();

            Elements elements = doc.select(tag + "[" + attr + "]");
            for (Element element : elements) {
                String link = element.attr(attr);
                if (link.isEmpty()) {
                    continue;
                }

                String fullUrl = doc.absUrl(attr); // JSoup resuelve URLs absolutas
                if (fullUrl.isEmpty()) { // Puede ocurrir si el atributo es javascript: void(0);
                    continue;
                }

                if (!isInternalLink(fullUrl, rootDomain)) {
                    continue;
                }

                String resourceLocalPath = localResourcePath(baseDir, fullUrl);
                if (resourceLocalPath == null) {
                    continue;
                }

                // Calcula la ruta relativa para el HTML
                File currentFile = new File(currentLocalPath);
                File resourceFile = new File(resourceLocalPath);
                String relativePath = currentFile.getParentFile().toURI().relativize(resourceFile.toURI()).getPath();

                // Quitar el primer './' si existe
                if (relativePath.startsWith("./")) {
                    relativePath = relativePath.substring(2);
                }

                if (isResourceUrl(fullUrl) || !tag.equals("a")) {
                    if (!visited.contains(fullUrl)) {
                        visited.add(fullUrl);
                        queue.add(fullUrl);
                        totalElementsToProcess++;
                    }
                    element.attr(attr, relativePath);
                } else if (tag.equals("a") && isHtmlPageUrl(fullUrl)) {
                    if (!visited.contains(fullUrl)) {
                        visited.add(fullUrl);
                        queue.add(fullUrl);
                        totalElementsToProcess++;
                    }
                    element.attr(attr, relativePath);
                }
            }
        }

        File output = new File(currentLocalPath);
        output.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(output)) {
            fos.write(doc.html().getBytes("UTF-8"));
        }
    }

    public String downloadSite(String startUrl, String outputDir) {
        String domain = "";
        try {
            domain = new URL(startUrl).getHost();
        } catch (Exception e) {
            log("[ERROR] URL inicial inválida: " + startUrl);
            return null;
        }

        String baseDir = new File(outputDir, sanitizePath(domain)).getAbsolutePath();
        new File(baseDir).mkdirs();

        queue.clear();
        visited.clear();
        totalElementsToProcess = 0;
        processedElements = 0;

        queue.add(startUrl);
        visited.add(startUrl);
        totalElementsToProcess++; // El primer elemento

        log("Iniciando descarga de: " + startUrl);
        log("Guardando en: " + baseDir);

        while (!queue.isEmpty()) {
            String currentUrl = queue.poll();
            processedElements++;
            updateProgress();
            log("Descargando: " + currentUrl);

            HttpGet request = new HttpGet(currentUrl);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    log(String.format("[ERROR] Falló la descarga de %s. Código: %d", currentUrl, statusCode));
                    continue;
                }

                String contentType = response.getFirstHeader("Content-Type") != null ?
                                     response.getFirstHeader("Content-Type").getValue() : "";

                String localPath = localResourcePath(baseDir, currentUrl);
                if (localPath == null) {
                    log(String.format("[ERROR] No se pudo determinar la ruta local para %s", currentUrl));
                    continue;
                }

                if (contentType.contains("text/html")) {
                    String htmlContent = EntityUtils.toString(response.getEntity());
                    processHtml(currentUrl, htmlContent, baseDir, startUrl);
                } else {
                    saveFile(currentUrl, localPath);
                }
            } catch (Exception e) {
                log(String.format("[ERROR] Falló el procesamiento de %s -> %s", currentUrl, e.getMessage()));
                // Puedes optar por no relanzar la excepción para seguir con otros archivos
            }
        }
        try {
            httpClient.close();
        } catch (IOException e) {
            log("[ERROR] Error al cerrar HttpClient: " + e.getMessage());
        }
        return baseDir;
    }
}
