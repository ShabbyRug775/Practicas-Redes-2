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
import java.util.concurrent.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public class WebCloner {
    private final Set<String> visited = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final ExecutorService executor;
    private final CloseableHttpClient httpClient;
    private final ReentrantLock progressLock = new ReentrantLock();
    
    // Configuración avanzada
    private final int maxThreads;
    private final int maxDepth;
    private final long delayBetweenRequests; // ms
    private final AtomicInteger activeThreads = new AtomicInteger(0);
    private final AtomicInteger totalProcessed = new AtomicInteger(0);
    private final AtomicInteger totalElements = new AtomicInteger(0);
    private final Map<String, Integer> depthMap = new ConcurrentHashMap<>();

    // Callbacks
    public interface LogCallback {
        void log(String message);
    }

    public interface ProgressCallback {
        void update(int current, int total, int activeThreads, int queueSize);
    }

    private final LogCallback logCallback;
    private final ProgressCallback progressCallback;

    // Extensiones de recursos
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

    public WebCloner(LogCallback logCallback, ProgressCallback progressCallback, 
                    int maxThreads, int maxDepth, long delayBetweenRequests) {
        this.logCallback = logCallback;
        this.progressCallback = progressCallback;
        this.maxThreads = maxThreads;
        this.maxDepth = maxDepth;
        this.delayBetweenRequests = delayBetweenRequests;
        
        this.httpClient = HttpClients.custom()
            .setMaxConnPerRoute(maxThreads)
            .setMaxConnTotal(maxThreads)
            .build();
            
        this.executor = Executors.newFixedThreadPool(maxThreads);
    }

    private void log(String message) {
        if (logCallback != null) {
            logCallback.log(message);
        }
    }

    private void updateProgress() {
        if (progressCallback != null) {
            progressCallback.update(
                totalProcessed.get(), 
                totalElements.get(), 
                activeThreads.get(), 
                queue.size()
            );
        }
    }

    private String sanitizePath(String path) {
        String sanitized = path.replaceAll("[\\\\:*?\"<>|]", "_");
        return sanitized.replaceAll("^/+|/+$", "");
    }

    private void saveFile(String url, String localPath) throws IOException {
        File file = new File(localPath);
        file.getParentFile().mkdirs();

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
            } else if (!path.contains(".")) {
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
            if (url.startsWith("#") || url.startsWith("javascript:")) {
                return false;
            }

            URI uri = new URI(url);
            String host = uri.getHost();

            // Aceptar URLs sin host (relativas)
            if (host == null) return true;

            // Normalizar dominios (www.ipn.mx e ipn.mx son iguales)
            host = host.replace("www.", "");
            String normalizedBase = baseDomain.replace("www.", "");

            // Aceptar cualquier subdominio de ipn.mx
            return host.equals(normalizedBase) || host.endsWith("." + normalizedBase);
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
        return !isResourceUrl(url);
    }

    private class DownloadTask implements Runnable {
        private final String url;
        private final String baseDir;
        private final String rootUrl;
        private final int currentDepth;

        public DownloadTask(String url, String baseDir, String rootUrl, int currentDepth) {
            this.url = url;
            this.baseDir = baseDir;
            this.rootUrl = rootUrl;
            this.currentDepth = currentDepth;
            log("[PROFUNDIDAD " + currentDepth + "] Procesando: " + url);
        }

        @Override
        public void run() {
            try {
                activeThreads.incrementAndGet();
                
                if (delayBetweenRequests > 0) {
                    Thread.sleep(delayBetweenRequests);
                }

                HttpGet request = new HttpGet(url);
                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    if (statusCode != 200) {
                        log(String.format("[ERROR] %s → Código: %d", url, statusCode));
                        return;
                    }

                    String contentType = response.getFirstHeader("Content-Type") != null ?
                                     response.getFirstHeader("Content-Type").getValue() : "";

                    String localPath = localResourcePath(baseDir, url);
                    if (localPath == null) {
                        log(String.format("[ERROR] No se pudo determinar ruta para %s", url));
                        return;
                    }

                    if (contentType.contains("text/html") && currentDepth < maxDepth) {
                        String htmlContent = EntityUtils.toString(response.getEntity());
                        processHtml(url, htmlContent, baseDir, rootUrl, currentDepth);
                    } else {
                        saveFile(url, localPath);
                    }
                }
            } catch (Exception e) {
                log(String.format("[ERROR] Procesando %s → %s", url, e.getMessage()));
            } finally {
                activeThreads.decrementAndGet();
                totalProcessed.incrementAndGet();
                updateProgress();
            }
        }
    }

    private void processHtml(String url, String htmlContent, String baseDir, String rootUrl, int currentDepth) 
        throws IOException {
        Document doc = Jsoup.parse(htmlContent, url);
        String currentLocalPath = localResourcePath(baseDir, url);
        String rootDomain = new URL(rootUrl).getHost();

        log("Procesando HTML de: " + url + " (Profundidad: " + currentDepth + ")");

        // Mapa de tags y atributos a buscar
        Map<String, String> tagAttrMap = new HashMap<>();
        tagAttrMap.put("a", "href");
        tagAttrMap.put("link", "href");
        tagAttrMap.put("script", "src");
        tagAttrMap.put("img", "src");
        tagAttrMap.put("iframe", "src");

        for (Map.Entry<String, String> entry : tagAttrMap.entrySet()) {
            String tag = entry.getKey();
            String attr = entry.getValue();

            Elements elements = doc.select(tag + "[" + attr + "]");
            log("Encontrados " + elements.size() + " elementos con tag " + tag);

            for (Element element : elements) {
                String link = element.attr(attr);
                if (link.isEmpty()) continue;

                String fullUrl = doc.absUrl(attr);
                if (fullUrl.isEmpty()) continue;

                rootDomain = new URL(rootUrl).getHost(); // Asegúrate de tener rootDomain aquí
                boolean isInternal = isInternalLink(fullUrl, rootDomain);
                boolean alreadyVisited = visited.contains(fullUrl);

                log(String.format("Enlace: %s, Es Interno: %b, Ya Visitado: %b, Profundidad actual: %d", 
                                  fullUrl, isInternal, alreadyVisited, currentDepth));

                if (!isInternal) {
                    log("Enlace externo ignorado: " + fullUrl);
                    continue;
                }

                if (alreadyVisited) {
                    log("Enlace ya visitado: " + fullUrl);
                    continue;
                }

                if (currentDepth < maxDepth) {
                    visited.add(fullUrl);
                    queue.add(fullUrl);
                    depthMap.put(fullUrl, currentDepth + 1);
                    totalElements.incrementAndGet();
                    log("Encolado (Profundidad " + (currentDepth + 1) + "): " + fullUrl);
                }

                String resourceLocalPath = localResourcePath(baseDir, fullUrl);
                if (resourceLocalPath != null) {
                    File currentFile = new File(currentLocalPath);
                    File resourceFile = new File(resourceLocalPath);
                    String relativePath = currentFile.getParentFile().toURI().relativize(resourceFile.toURI()).getPath();
                    element.attr(attr, relativePath);
                }
            }
        }

        // Guardar el HTML modificado
        File output = new File(currentLocalPath);
        output.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(output)) {
            fos.write(doc.html().getBytes("UTF-8"));
        }
    }

    public String downloadSite(String startUrl, String outputDir) {
        String domain;
        try {
            domain = new URL(startUrl).getHost();
        } catch (Exception e) {
            log("[ERROR] URL inicial inválida: " + startUrl);
            return null;
        }

        String baseDir = new File(outputDir, sanitizePath(domain)).getAbsolutePath();
        new File(baseDir).mkdirs();

        visited.clear();
        queue.clear();
        totalProcessed.set(0);
        totalElements.set(0);
        depthMap.clear();

        visited.add(startUrl);
        queue.add(startUrl);
        totalElements.incrementAndGet();
        depthMap.put(startUrl, 0);

        log("Iniciando descarga de: " + startUrl);
        log("Configuración: " + maxThreads + " hilos, profundidad máxima: " + maxDepth);

        for (int i = 0; i < Math.min(maxThreads, totalElements.get()); i++) {
            scheduleNextTask(baseDir, startUrl);
        }

        while ((activeThreads.get() > 0 || !queue.isEmpty()) && !Thread.currentThread().isInterrupted()) {
            scheduleNextTask(baseDir, startUrl);
            try {
                Thread.sleep(100); // Reducir el tiempo de espera
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        shutdown();
        return baseDir;
    }

    private void scheduleNextTask(String baseDir, String rootUrl) {
        // Buscar la URL con menor profundidad primero
        String nextUrl = null;
        int minDepth = Integer.MAX_VALUE;

        // Itera sobre el depthMap para encontrar la URL en la cola con la menor profundidad
        for (Map.Entry<String, Integer> entry : depthMap.entrySet()) {
            if (queue.contains(entry.getKey()) && entry.getValue() < minDepth) {
                nextUrl = entry.getKey();
                minDepth = entry.getValue();
            }
        }

        if (nextUrl != null) {
            queue.remove(nextUrl); // Elimina la URL de la cola
            if (minDepth <= maxDepth) { // Verifica si la profundidad es menor o igual a la máxima
                executor.execute(new DownloadTask(nextUrl, baseDir, rootUrl, minDepth));
            } else {
                log("[INFO] URL " + nextUrl + " ignorada por exceder la profundidad máxima (" + minDepth + ")");
                totalProcessed.incrementAndGet(); // Contar como procesada si se ignora por profundidad
                updateProgress();
            }
        }
    }

    private void shutdown() {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            httpClient.close();
        } catch (Exception e) {
            log("[ERROR] Al cerrar recursos: " + e.getMessage());
        }
    }
}