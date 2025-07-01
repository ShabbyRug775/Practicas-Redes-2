package HttpSnifer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class Practica4 {
    private JFrame frame;
    private JTextArea outputArea;
    private JButton startSnifferButton;
    private JButton stopSnifferButton;
    private JButton startServerButton;
    private JButton stopServerButton;
    private boolean isSnifferRunning = false;
    private boolean isServerRunning = false;
    private ExecutorService executorService;
    private ServerSocket proxySocket;
    private ServerSocket serverSocket;

    // Expresiones regulares para métodos HTTP
    private static final Pattern GET_PATTERN = Pattern.compile("GET\\s+(.*?)\\s+HTTP/1\\.[01]");
    private static final Pattern POST_PATTERN = Pattern.compile("POST\\s+(.*?)\\s+HTTP/1\\.[01]");
    private static final Pattern PUT_PATTERN = Pattern.compile("PUT\\s+(.*?)\\s+HTTP/1\\.[01]");
    private static final Pattern DELETE_PATTERN = Pattern.compile("DELETE\\s+(.*?)\\s+HTTP/1\\.[01]");
    private static final Pattern PDF_PATTERN = Pattern.compile("(GET|POST|PUT)\\s+.*\\.pdf(\\?.*?)?\\s+HTTP/1\\.[01]", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_RESPONSE_PATTERN = Pattern.compile("HTTP/1\\.[01]\\s+(\\d{3})\\s+([^\\r\\n]*)");

    // Códigos de estado HTTP
    private static final Map<Integer, String> HTTP_ERROR_CODES = new HashMap<>();
    static {
        HTTP_ERROR_CODES.put(200, "OK");
        HTTP_ERROR_CODES.put(201, "Created");
        HTTP_ERROR_CODES.put(204, "No Content");
        HTTP_ERROR_CODES.put(301, "Moved Permanently");
        HTTP_ERROR_CODES.put(302, "Found");
        HTTP_ERROR_CODES.put(400, "Bad Request");
        HTTP_ERROR_CODES.put(401, "Unauthorized");
        HTTP_ERROR_CODES.put(403, "Forbidden");
        HTTP_ERROR_CODES.put(404, "Not Found");
        HTTP_ERROR_CODES.put(405, "Method Not Allowed");
        HTTP_ERROR_CODES.put(500, "Internal Server Error");
    }

    public Practica4() {
        initialize();
    }

    private void initialize() {
        frame = new JFrame("HTTP Sniffer + Servidor");
        frame.setSize(900, 650);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel mainPanel = new JPanel(new BorderLayout());
        
        // Panel de control
        JPanel controlPanel = new JPanel(new GridLayout(1, 2));
        
        // Panel del Sniffer
        JPanel snifferPanel = new JPanel(new FlowLayout());
        snifferPanel.setBorder(BorderFactory.createTitledBorder("Sniffer HTTP"));
        startSnifferButton = new JButton("Iniciar Sniffer (8080)");
        stopSnifferButton = new JButton("Detener Sniffer");
        stopSnifferButton.setEnabled(false);
        snifferPanel.add(startSnifferButton);
        snifferPanel.add(stopSnifferButton);
        
        // Panel del Servidor
        JPanel serverPanel = new JPanel(new FlowLayout());
        serverPanel.setBorder(BorderFactory.createTitledBorder("Servidor Web"));
        startServerButton = new JButton("Iniciar Servidor (5000)");
        stopServerButton = new JButton("Detener Servidor");
        stopServerButton.setEnabled(false);
        serverPanel.add(startServerButton);
        serverPanel.add(stopServerButton);
        
        controlPanel.add(snifferPanel);
        controlPanel.add(serverPanel);

        // Área de salida
        outputArea = new JTextArea();
        outputArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(outputArea);

        mainPanel.add(controlPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        frame.add(mainPanel);

        // Configurar acciones de los botones
        startSnifferButton.addActionListener(e -> startSniffer());
        stopSnifferButton.addActionListener(e -> stopSniffer());
        startServerButton.addActionListener(e -> startServer());
        stopServerButton.addActionListener(e -> stopServer());
    }

    private void startSniffer() {
        if (isSnifferRunning) return;

        isSnifferRunning = true;
        startSnifferButton.setEnabled(false);
        stopSnifferButton.setEnabled(true);
        executorService = Executors.newFixedThreadPool(10);

        appendToOutput("\n🔍 Sniffer HTTP iniciado en puerto 8080");
        appendToOutput("Configura tu navegador o aplicación para usar localhost:8080 como proxy");

        new Thread(() -> {
            try {
                proxySocket = new ServerSocket(8080);
                while (isSnifferRunning) {
                    Socket clientSocket = proxySocket.accept();
                    executorService.submit(() -> handleClientRequest(clientSocket));
                }
            } catch (IOException e) {
                if (isSnifferRunning) {
                    appendToOutput("Error en el sniffer: " + e.getMessage());
                }
            }
        }).start();
    }

    private void stopSniffer() {
        if (!isSnifferRunning) return;

        isSnifferRunning = false;
        try {
            if (proxySocket != null) {
                proxySocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (executorService != null) {
            executorService.shutdown();
        }

        startSnifferButton.setEnabled(true);
        stopSnifferButton.setEnabled(false);
        appendToOutput("\nSniffer detenido.");
    }

    private void startServer() {
        if (isServerRunning) return;

        isServerRunning = true;
        startServerButton.setEnabled(false);
        stopServerButton.setEnabled(true);

        appendToOutput("\n🌍 Servidor web iniciado en puerto 5000");
        appendToOutput("Accede a http://localhost:5000/test o http://localhost:5000/archivo.pdf");

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(5000);
                while (isServerRunning) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleServerRequest(clientSocket)).start();
                }
            } catch (IOException e) {
                if (isServerRunning) {
                    appendToOutput("Error en el servidor: " + e.getMessage());
                }
            }
        }).start();
    }

    private void stopServer() {
        if (!isServerRunning) return;

        isServerRunning = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        startServerButton.setEnabled(true);
        stopServerButton.setEnabled(false);
        appendToOutput("\nServidor detenido.");
    }

    private void handleClientRequest(Socket clientSocket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            String requestLine = in.readLine();
            if (requestLine == null) return;

            // Analizar la petición
            processHttpRequest(requestLine);

            // Extraer el destino real (eliminando el proxy de la URL si está presente)
            String realUrl = requestLine.split(" ")[1].replace("http://localhost:8080/", "");
            String host = "localhost";
            int port = 5000;
            
            if (realUrl.startsWith("http://")) {
                // Extraer host y puerto de la URL completa
                String[] parts = realUrl.replaceFirst("http://", "").split("/")[0].split(":");
                host = parts[0];
                port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;
                realUrl = realUrl.substring(realUrl.indexOf("/", 7));
            }

            // Conectar al servidor real
            try (Socket serverSocket = new Socket(host, port);
                 PrintWriter out = new PrintWriter(serverSocket.getOutputStream(), true);
                 BufferedReader serverIn = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));
                 PrintWriter clientOut = new PrintWriter(clientSocket.getOutputStream(), true)) {
                
                // Reconstruir la petición original
                out.println(requestLine.split(" ")[0] + " " + realUrl + " " + requestLine.split(" ")[2]);

                // Reenviar encabezados
                String headerLine;
                while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
                    out.println(headerLine);
                }
                out.println();

                // Leer respuesta del servidor
                String responseLine = serverIn.readLine();
                if (responseLine != null) {
                    processHttpResponse(responseLine);
                    clientOut.println(responseLine);

                    // Reenviar el resto de la respuesta
                    String line;
                    while ((line = serverIn.readLine()) != null) {
                        clientOut.println(line);
                    }
                }
            }
        } catch (IOException e) {
            appendToOutput("Error en conexión: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void handleServerRequest(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {
            
            String requestLine = in.readLine();
            if (requestLine == null) return;

            // Leer encabezados (aunque no los usaremos en este ejemplo simple)
            while (in.readLine() != null && !in.readLine().isEmpty());

            if (requestLine.startsWith("GET /test ")) {
                out.println("HTTP/1.1 200 OK");
                out.println("Content-Type: text/plain");
                out.println();
                out.println("GET OK");
            } 
            else if (requestLine.startsWith("POST /test ")) {
                out.println("HTTP/1.1 200 OK");
                out.println("Content-Type: text/plain");
                out.println();
                out.println("POST OK");
            }
            else if (requestLine.startsWith("PUT /test ")) {
                out.println("HTTP/1.1 200 OK");
                out.println("Content-Type: text/plain");
                out.println();
                out.println("PUT OK");
            }
            else if (requestLine.startsWith("DELETE /test ")) {
                out.println("HTTP/1.1 200 OK");
                out.println("Content-Type: text/plain");
                out.println();
                out.println("DELETE OK");
            }
            else if (requestLine.startsWith("GET /archivo.pdf ")) {
                out.println("HTTP/1.1 200 OK");
                out.println("Content-Type: application/pdf");
                out.println("Content-Disposition: inline; filename=\"archivo.pdf\"");
                out.println();
                out.println("PDF simulado (contenido binario iría aquí)");
            }
            else {
                out.println("HTTP/1.1 404 Not Found");
                out.println("Content-Type: text/plain");
                out.println();
                out.println("Ruta no encontrada");
            }
        } catch (IOException e) {
            appendToOutput("Error en el servidor: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void processHttpRequest(String request) {
        if (PDF_PATTERN.matcher(request).find()) {
            appendFormattedMessage("📄 Petición PDF detectada", request);
        }
        else if (GET_PATTERN.matcher(request).find()) {
            appendFormattedMessage("🌐 Petición HTTP GET detectada", request);
        }
        else if (POST_PATTERN.matcher(request).find()) {
            appendFormattedMessage("📦 Petición HTTP POST detectada", request);
        }
        else if (PUT_PATTERN.matcher(request).find()) {
            appendFormattedMessage("📤 Petición HTTP PUT detectada", request);
        }
        else if (DELETE_PATTERN.matcher(request).find()) {
            appendFormattedMessage("🗑️ Petición HTTP DELETE detectada", request);
        }
    }

    private void processHttpResponse(String response) {
        Matcher matcher = HTTP_RESPONSE_PATTERN.matcher(response);
        if (matcher.find()) {
            int statusCode = Integer.parseInt(matcher.group(1));
            String statusMessage = matcher.group(2);
            String tipo = (200 <= statusCode && statusCode < 300) ? "Éxito" : 
                        (statusCode >= 400) ? "Error" : "Otro";
            String emoji = tipo.equals("Éxito") ? "✅" : 
                         tipo.equals("Error") ? "❌" : "ℹ️";
            
            String message = String.format("\n%s Respuesta HTTP detectada [%s]:\n" +
                    "--------------------------------------------\n" +
                    "Código: %d - %s\n" +
                    "Mensaje del servidor: %s\n" +
                    "--------------------------------------------\n",
                    emoji, tipo, statusCode, 
                    HTTP_ERROR_CODES.getOrDefault(statusCode, "Otro"),
                    statusMessage);
            
            appendToOutput(message);
        }
    }

    private void appendFormattedMessage(String title, String content) {
        String message = String.format("\n%s:\n--------------------------------------------\n%s\n--------------------------------------------\n", 
                                     title, content.split("\r\n\r\n")[0]);
        appendToOutput(message);
    }

    private void appendToOutput(final String text) {
        SwingUtilities.invokeLater(() -> {
            outputArea.append(text + "\n");
            outputArea.setCaretPosition(outputArea.getDocument().getLength());
        });
    }

    public void show() {
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }

            Practica4 app = new Practica4();
            app.show();
        });
    }
}