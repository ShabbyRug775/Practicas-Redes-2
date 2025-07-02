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
    // CAMBIO CLAVE: Usamos un Deque como pila para LIFO (DFS)
    private final Deque<String> stack = new LinkedList<>(); 
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
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
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
                // CAMBIO: tamaño de la pila
                stack.size() 
            );
        }
    }

    // --- CAMBIO CLAVE: sanitizePath mejorado ---
    private String sanitizePath(String path) {
        // Elimina caracteres inválidos en nombres de archivo (en la mayoría de los SO)
        String sanitized = path.replaceAll("[\\\\:*?\"<>|]", "_");
        // Reemplaza múltiples barras con una sola
        sanitized = sanitized.replaceAll("/+", "/");
        
        // Elimina la barra inicial si no es la raíz, pero si es solo "/", lo deja como "".
        if (sanitized.startsWith("/") && sanitized.length() > 1) {
            sanitized = sanitized.substring(1);
        } else if (sanitized.equals("/")) {
            sanitized = ""; // Si es solo "/", se convierte en cadena vacía para que File(baseDir, "") funcione.
        }
        // No eliminar la barra final aquí; localResourcePath la usa para identificar directorios.
        return sanitized;
    }
    // --- FIN DEL CAMBIO EN sanitizePath ---

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

    // --- CAMBIO CLAVE: Lógica mejorada para localResourcePath ---
    private String localResourcePath(String baseDir, String resourceUrl) {
        try {
            URL url = new URL(resourceUrl);
            String path = url.getPath(); 
            String query = url.getQuery();

            String sanitizedPathSegment = sanitizePath(path);
            
            // Si hay parámetros de consulta, añadirlos al nombre del archivo de forma segura
            if (query != null && !query.isEmpty()) {
                sanitizedPathSegment += "__query__" + sanitizePath(query); // Usar un separador claro
            }

            File outputFile;

            // Caso 1: La URL apunta a un directorio o a la raíz (termina en / o es una cadena vacía después de sanitizar)
            // Ejemplos: "example.com/" -> "index.html", "example.com/about/" -> "about/index.html"
            if (sanitizedPathSegment.isEmpty() || sanitizedPathSegment.endsWith("/")) {
                // Si es la raíz del dominio, el path debe ser "". Si no, se crea la carpeta.
                String dirPath = sanitizedPathSegment.isEmpty() ? "" : sanitizedPathSegment;
                outputFile = new File(new File(baseDir, dirPath), "index.html");
            } else {
                // Caso 2: La URL tiene un nombre de archivo, verificar la extensión
                // Se busca el último punto en la RUTA SANITIZADA para determinar la extensión
                int lastDotIndex = sanitizedPathSegment.lastIndexOf('.');
                
                // Si hay un punto, y no está al inicio/final, y la ruta no termina en barra (lo que indicaría un directorio)
                if (lastDotIndex > 0 && lastDotIndex < sanitizedPathSegment.length() - 1 && !sanitizedPathSegment.endsWith("/")) {
                    String extension = sanitizedPathSegment.substring(lastDotIndex).toLowerCase();
                    // Convertir extensiones dinámicas a .html
                    if (extension.equals(".php") || extension.equals(".asp") || extension.equals(".aspx") || extension.equals(".htm")) {
                        String pathWithoutExtension = sanitizedPathSegment.substring(0, lastDotIndex);
                        outputFile = new File(baseDir, pathWithoutExtension + ".html");
                    } else {
                        // Mantener la extensión original para otros recursos (CSS, JS, imágenes, etc.)
                        outputFile = new File(baseDir, sanitizedPathSegment);
                    }
                } else {
                    // Caso 3: La URL no tiene una extensión explícita o termina en punto (ej. /about, /products/item1, /download.v1.)
                    // Se asume que es una página HTML limpia y se le añade .html
                    outputFile = new File(baseDir, sanitizedPathSegment + ".html");
                }
            }
            return outputFile.getAbsolutePath();

        } catch (Exception e) {
            log("[ERROR] Error al calcular ruta local para: " + resourceUrl + " -> " + e.getMessage());
            return null;
        }
    }
    // --- FIN DEL CAMBIO EN localResourcePath ---

    private boolean isInternalLink(String url, String baseDomain) {
        try {
            if (url.startsWith("#") || url.startsWith("javascript:")) {
                return false;
            }

            URI uri = new URI(url);
            String host = uri.getHost();

            // Aceptar URLs relativas (sin host)
            if (host == null) return true;

            // Normalizar dominios (ignorar www)
            String normalizedHost = host.replace("www.", ""); // ← Copia efectivamente final
            String normalizedBase = baseDomain.replace("www.", "");

            // Dominios adicionales permitidos (ej: para Yahoo)
            Set<String> allowedDomains = Set.of(
                normalizedBase,
                "yimg.com",       // Recursos de Yahoo
                "s.yimg.com",     // CDN de Yahoo
                "fonts.googleapis.com", // Fuentes comunes
                "cdnjs.cloudflare.com"  // Librerías JS
            );

            // Usar normalizedHost (effectively final) en el lambda
            return allowedDomains.stream().anyMatch(domain -> 
                normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain)
            );
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
        log("Ruta local del HTML actual: " + currentLocalPath); // Log adicional para depuración

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

                boolean isInternal = isInternalLink(fullUrl, rootDomain);
                boolean alreadyVisited = visited.contains(fullUrl);

                log(String.format("  Enlace original: %s, URL Absoluta: %s, Es Interno: %b, Ya Visitado: %b",
                                  link, fullUrl, isInternal, alreadyVisited));

                if (!isInternal) {
                    log("  [INFO] Enlace externo ignorado para reescritura: " + fullUrl);
                    continue; // No reescribir enlaces externos
                }

                // La reescritura del enlace debe ocurrir para TODOS los enlaces internos,
                // independientemente de si ya se visitaron para el rastreo.
                String resourceLocalPath = localResourcePath(baseDir, fullUrl);
                log("  Ruta local calculada para el recurso: " + resourceLocalPath); // Log adicional

                if (resourceLocalPath != null) {
                    File currentFile = new File(currentLocalPath);
                    File resourceFile = new File(resourceLocalPath);

                    // Asegurarse de que el directorio padre del archivo HTML actual existe
                    if (currentFile.getParentFile() != null && !currentFile.getParentFile().exists()) {
                        currentFile.getParentFile().mkdirs(); 
                    }
                    
                    String relativePath = currentFile.getParentFile().toURI().relativize(resourceFile.toURI()).getPath();
                    
                    // Log del cambio antes y después
                    log(String.format("  [REWRITE] Reescribiendo: '%s' (antes) -> '%s' (después)",
                                      element.attr(attr), relativePath));
                    element.attr(attr, relativePath); // Modificar el atributo del elemento Jsoup
                } else {
                    log("  [WARNING] No se pudo determinar la ruta local para reescritura de: " + fullUrl);
                }
                
                // Lógica de rastreo: solo añadir a la pila si es interno, no ha sido visitado y está dentro de la profundidad máxima
                if (!alreadyVisited && currentDepth < maxDepth) {
                    visited.add(fullUrl);
                    stack.push(fullUrl); // CAMBIO: Añadir a la pila (simula push)
                    depthMap.put(fullUrl, currentDepth + 1);
                    totalElements.incrementAndGet();
                    log("  [CRAWL] Encolado para descarga (Profundidad " + (currentDepth + 1) + "): " + fullUrl);
                } else if (alreadyVisited) {
                    log("  [CRAWL] Enlace ya visitado (no se encola de nuevo): " + fullUrl);
                } else if (currentDepth >= maxDepth) {
                     log("  [CRAWL] Enlace " + fullUrl + " ignorado por exceder la profundidad máxima.");
                }
            }
        }

        // Guardar el HTML modificado
        File output = new File(currentLocalPath);
        output.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(output)) {
            fos.write(doc.html().getBytes("UTF-8"));
            log("HTML modificado guardado en: " + currentLocalPath); // Log adicional
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
        // CAMBIO: limpiar la pila
        stack.clear(); 
        totalProcessed.set(0);
        totalElements.set(0);
        depthMap.clear();

        visited.add(startUrl);
        // CAMBIO: añadir a la pila
        stack.push(startUrl); 
        totalElements.incrementAndGet();
        depthMap.put(startUrl, 0);

        log("Iniciando descarga de: " + startUrl);
        log("Configuración: " + maxThreads + " hilos, profundidad máxima: " + maxDepth);

        for (int i = 0; i < Math.min(maxThreads, totalElements.get()); i++) {
            scheduleNextTask(baseDir, startUrl);
        }

        // CAMBIO: verificar si la pila está vacía
        while ((activeThreads.get() > 0 || !stack.isEmpty()) && !Thread.currentThread().isInterrupted()) { 
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
        // CAMBIO CLAVE: Eliminar la lógica de búsqueda por menor profundidad
        // Ahora simplemente tomamos el elemento superior de la pila (LIFO)
        String nextUrl = null;
        if (!stack.isEmpty()) {
            nextUrl = stack.pop(); // CAMBIO: obtener de la pila (simula pop)
        }

        if (nextUrl != null) {
            // La profundidad se obtiene del depthMap, no es un factor para la selección LIFO
            int currentDepthForNextUrl = depthMap.getOrDefault(nextUrl, 0);

            if (currentDepthForNextUrl <= maxDepth) { // Verifica si la profundidad es menor o igual a la máxima
                executor.execute(new DownloadTask(nextUrl, baseDir, rootUrl, currentDepthForNextUrl));
            } else {
                log("[INFO] URL " + nextUrl + " ignorada por exceder la profundidad máxima (" + currentDepthForNextUrl + ")");
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