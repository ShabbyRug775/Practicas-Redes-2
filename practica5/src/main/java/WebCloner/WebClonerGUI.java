package WebCloner;

import javax.swing.*;
import java.awt.*;
import java.io.File;

public class WebClonerGUI extends JFrame {

    private JTextField urlField;
    private JTextArea logArea;
    private JButton startButton;
    private JProgressBar progressBar;

    public WebClonerGUI() {
        setTitle("Clonador de Sitios Web");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null); // Centrar la ventana

        initComponents();
        addListeners();
    }

    private void initComponents() {
        setLayout(new BorderLayout());

        // Panel superior para la URL y el botón
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("URL del sitio web:"));
        urlField = new JTextField(40);
        urlField.setText("https://www.example.com"); // URL por defecto
        topPanel.add(urlField);
        startButton = new JButton("Clonar Sitio");
        topPanel.add(startButton);
        add(topPanel, BorderLayout.NORTH);

        // Área de log
        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);
        add(scrollPane, BorderLayout.CENTER);

        // Barra de progreso
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        add(progressBar, BorderLayout.SOUTH);
    }

    private void addListeners() {
        startButton.addActionListener(e -> {
            String startUrl = urlField.getText().trim();
            if (startUrl.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Por favor, introduce una URL.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Deshabilitar botón mientras se clona
            startButton.setEnabled(false);
            logArea.setText(""); // Limpiar log anterior
            progressBar.setValue(0);
            log("Iniciando clonación de: " + startUrl);

            // Ejecutar la clonación en un hilo separado con SwingWorker
            new SwingWorker<Void, String>() {
                private String clonedPath = "";

                @Override
                protected Void doInBackground() throws Exception {
                    try {
                        WebCloner cloner = new WebCloner(
                            message -> publish(message), // Callback para logs
                            (current, max) -> { // Callback para progreso
                                // Calcular el porcentaje de progreso y publicarlo
                                if (max > 0) {
                                    int progressPercentage = (int) ((double) current / max * 100);
                                    // Usar el método setProgress de SwingWorker
                                    setProgress(progressPercentage);
                                }
                            }
                        );
                        String outputDir = System.getProperty("user.dir"); // Directorio actual
                        clonedPath = cloner.downloadSite(startUrl, outputDir);
                        publish("✅ Sitio clonado exitosamente en:\n" + clonedPath);
                        publish("📂 Abre el archivo 'index.html' en esa carpeta para navegar localmente.");

                    } catch (Exception ex) {
                        publish("[ERROR] " + ex.getMessage());
                        ex.printStackTrace();
                    }
                    return null;
                }

                @Override
                protected void process(java.util.List<String> chunks) {
                    // Este método se ejecuta en el EDT y es donde recibes las publicaciones de publish()
                    for (String message : chunks) {
                        log(message);
                    }
                    // Actualizar la barra de progreso aquí, el getProgress() es de SwingWorker
                    progressBar.setValue(getProgress());
                }

                @Override
                protected void done() {
                    startButton.setEnabled(true); // Habilitar botón de nuevo
                    try {
                        get(); // Para propagar cualquier excepción que haya ocurrido en doInBackground
                    } catch (Exception ex) {
                        log("[ERROR FATAL] " + ex.getMessage());
                    }
                    // Opcional: Abrir la carpeta donde se guardó el sitio
                    if (!clonedPath.isEmpty()) {
                        try {
                            Desktop.getDesktop().open(new File(clonedPath));
                        } catch (Exception ex) {
                            log("No se pudo abrir la carpeta automáticamente: " + ex.getMessage());
                        }
                    }
                }
            }.execute(); // Iniciar el SwingWorker
        });
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new WebClonerGUI().setVisible(true);
        });
    }
}