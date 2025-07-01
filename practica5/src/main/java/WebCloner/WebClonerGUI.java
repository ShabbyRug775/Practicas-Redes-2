package WebCloner;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebClonerGUI extends JFrame {
    private JTextField urlField;
    private JTextArea logArea;
    private JButton startButton;
    private JButton stopButton;
    private JProgressBar progressBar;
    private JLabel statsLabel;
    private JSpinner threadsSpinner;
    private JSpinner depthSpinner;
    private JSpinner delaySpinner;
    private AtomicBoolean stopRequested = new AtomicBoolean(false);

    public WebClonerGUI() {
        setTitle("Clonador de Sitios Web Avanzado");
        setSize(900, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        initComponents();
        addListeners();
    }

    private void initComponents() {
        setLayout(new BorderLayout());

        // Panel de configuración
        JPanel configPanel = new JPanel(new GridLayout(1, 4, 5, 5));
        configPanel.add(new JLabel("Hilos:"));
        threadsSpinner = new JSpinner(new SpinnerNumberModel(4, 1, 16, 1));
        configPanel.add(threadsSpinner);
        
        configPanel.add(new JLabel("Profundidad:"));
        depthSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));
        configPanel.add(depthSpinner);
        
        configPanel.add(new JLabel("Retardo (ms):"));
        delaySpinner = new JSpinner(new SpinnerNumberModel(200, 0, 5000, 100));
        configPanel.add(delaySpinner);

        // Panel superior
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(configPanel, BorderLayout.NORTH);
        
        JPanel urlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        urlPanel.add(new JLabel("URL:"));
        urlField = new JTextField(40);
        urlField.setText("https://www.example.com");
        urlPanel.add(urlField);
        
        startButton = new JButton("Iniciar");
        stopButton = new JButton("Detener");
        stopButton.setEnabled(false);
        
        JPanel buttonPanel = new JPanel();
        buttonPanel.add(startButton);
        buttonPanel.add(stopButton);
        
        urlPanel.add(buttonPanel);
        topPanel.add(urlPanel, BorderLayout.CENTER);
        add(topPanel, BorderLayout.NORTH);

        // Área de log
        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);
        add(scrollPane, BorderLayout.CENTER);

        // Panel inferior
        JPanel bottomPanel = new JPanel(new BorderLayout());
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        bottomPanel.add(progressBar, BorderLayout.CENTER);
        
        statsLabel = new JLabel("Listo");
        statsLabel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        bottomPanel.add(statsLabel, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void addListeners() {
        startButton.addActionListener(e -> startDownload());
        stopButton.addActionListener(e -> stopRequested.set(true));
    }

    private void startDownload() {
        String startUrl = urlField.getText().trim();
        if (startUrl.isEmpty() || !isValidUrl(startUrl)) {
            JOptionPane.showMessageDialog(this, "URL inválida", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int threads = (int) threadsSpinner.getValue();
        int depth = (int) depthSpinner.getValue();
        long delay = ((Number) delaySpinner.getValue()).longValue();

        stopRequested.set(false);
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        logArea.setText("");
        progressBar.setValue(0);
        statsLabel.setText("Iniciando...");

        new SwingWorker<Void, String>() {
            private String clonedPath = "";
            private long startTime;

            @Override
            protected Void doInBackground() {
                startTime = System.currentTimeMillis();
                try {
                    WebCloner cloner = new WebCloner(
                        this::publish,
                        (current, total, active, queueSize) -> {
                            int progress = total > 0 ? (int) ((double) current / total * 100) : 0;
                            setProgress(progress);
                            publish(String.format("[Progreso] %d/%d | Hilos: %d | Cola: %d", 
                                current, total, active, queueSize));
                        },
                        threads, depth, delay
                           
                    );

                    String outputDir = showDirectoryChooser();
                    if (outputDir == null) {
                        publish("Operación cancelada");
                        return null;
                    }

                    clonedPath = cloner.downloadSite(startUrl, outputDir);
                    if (clonedPath != null) {
                        publish("Sitio clonado exitosamente en:\n" + clonedPath);
                    }
                } catch (Exception ex) {
                    publish("[ERROR] " + ex.getMessage());
                    ex.printStackTrace();
                }
                return null;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String message : chunks) {
                    if (message.startsWith("[Progreso]")) {
                        statsLabel.setText(message);
                    } else {
                        logArea.append(message + "\n");
                    }
                }
                progressBar.setValue(getProgress());
            }

            @Override
            protected void done() {
                long duration = (System.currentTimeMillis() - startTime) / 1000;
                statsLabel.setText(String.format("Completado en %d segundos", duration));
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                
                if (clonedPath != null && !clonedPath.isEmpty()) {
                    try {
                        Desktop.getDesktop().open(new File(clonedPath));
                    } catch (Exception ex) {
                        log("No se pudo abrir la carpeta: " + ex.getMessage());
                    }
                }
            }
        }.execute();
    }

    private String showDirectoryChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Seleccionar directorio de destino");
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile().getAbsolutePath();
        }
        return null;
    }

    private boolean isValidUrl(String url) {
        try {
            new URL(url).toURI();
            return url.startsWith("http://") || url.startsWith("https://");
        } catch (Exception e) {
            return false;
        }
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            WebClonerGUI gui = new WebClonerGUI();
            gui.setVisible(true);
        });
    }
}