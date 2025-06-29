package servidor;

import java.net.*;
import java.io.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.tree.*;
import javax.swing.event.*;
import javax.swing.filechooser.*;
import java.util.concurrent.*;

public class Servidor {

    // Componentes de la interfaz gráfica y variables de estado
    private static JTextArea statusArea;
    private static File selectedFile;
    private static String ruta_archivos;
    private static ServerSocket serverSocket;
    private static DefaultMutableTreeNode rootNode;
    private static JTree fileTree;
    private static DefaultTreeModel treeModel;
    private static ExecutorService clientThreadPool;
    private static ExecutorService taskThreadPool;
    private static final int MAX_CLIENTS = 20;

    public static void main(String[] args) {
        // Configuración de las albercas de hilos
        clientThreadPool = Executors.newFixedThreadPool(MAX_CLIENTS);
        taskThreadPool = Executors.newCachedThreadPool();

        // Configuración de la ventana principal
        JFrame frame = new JFrame("Servidor de Archivos");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(700, 600);
        frame.setLayout(new BorderLayout());
        
        // Panel superior para configuración del servidor
        JPanel clientPanel = new JPanel(new FlowLayout());
        JLabel clientLabel = new JLabel("Cliente:");
        JTextField clientField = new JTextField("127.0.0.1", 15);
        JLabel portLabel = new JLabel("Puerto:");
        JTextField portField = new JTextField("8001", 5);
        clientPanel.add(clientLabel);
        clientPanel.add(clientField);
        clientPanel.add(portLabel);
        clientPanel.add(portField);
        
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        JSplitPane fileSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        rootNode = new DefaultMutableTreeNode("Sistema de Archivos");
        treeModel = new DefaultTreeModel(rootNode);
        fileTree = new JTree(treeModel);
        fileTree.setEditable(false);
        JScrollPane treeScroll = new JScrollPane(fileTree);

        // Panel superior con botones de acción
        JPanel infoPanel = new JPanel(new BorderLayout());
        JButton sendButton = new JButton("Enviar Archivo Seleccionado");
        JButton refreshButton = new JButton("Actualizar");
        JButton createButton = new JButton("Crear Archivo/Carpeta");
        JButton deleteButton = new JButton("Eliminar Seleccionado");
        JButton renameButton = new JButton("Renombrar Seleccionado");
        JPanel buttonPanel = new JPanel(new GridLayout(5, 1));
        buttonPanel.add(sendButton);
        buttonPanel.add(refreshButton);
        buttonPanel.add(createButton);
        buttonPanel.add(deleteButton);
        buttonPanel.add(renameButton);
        
        JLabel fileInfoLabel = new JLabel("Archivo seleccionado: Ninguno");
        infoPanel.add(fileInfoLabel, BorderLayout.NORTH);
        infoPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        fileSplitPane.setLeftComponent(treeScroll);
        fileSplitPane.setRightComponent(infoPanel);
        fileSplitPane.setDividerLocation(400);
        
        // Configuración del área de estado
        statusArea = new JTextArea();
        statusArea.setEditable(false);
        JScrollPane statusScroll = new JScrollPane(statusArea);
        
        mainSplitPane.setTopComponent(fileSplitPane);
        mainSplitPane.setBottomComponent(statusScroll);
        mainSplitPane.setDividerLocation(350);
        
        JButton loadFsButton = new JButton("Cargar Sistema de Archivos");
        clientPanel.add(loadFsButton);
        
        frame.add(clientPanel, BorderLayout.NORTH);
        frame.add(mainSplitPane, BorderLayout.CENTER);

        // Listener para selección de elementos en el árbol
        fileTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            if (node == null) {
                return;
            }

            Object nodeInfo = node.getUserObject();
            if (nodeInfo instanceof File) {
                File file = (File) nodeInfo;
                if (!file.isDirectory()) {
                    selectedFile = file;
                    fileInfoLabel.setText("Archivo seleccionado: " + file.getName() + " (" + file.length() + " bytes)");
                } else {
                    selectedFile = null;
                    fileInfoLabel.setText("Directorio seleccionado: " + file.getName());
                }
            }
        });

        // Listener para el botón de selección de carpeta
        loadFsButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int returnValue = fileChooser.showOpenDialog(null);
            if (returnValue == JFileChooser.APPROVE_OPTION) {
                ruta_archivos = fileChooser.getSelectedFile().getAbsolutePath();
                if (!ruta_archivos.endsWith(File.separator)) {
                    ruta_archivos += File.separator;
                }
                updateFileTree();
                statusArea.append("Carpeta de destino seleccionada: " + ruta_archivos + "\n");
                startServer();
            }
        });

        // Listener para el botón de actualización
        refreshButton.addActionListener(e -> {
            if (ruta_archivos != null && !ruta_archivos.isEmpty()) {
                updateFileTree();
                statusArea.append("Contenido actualizado\n");
            }
        });

        // Listener para el botón de creación
        createButton.addActionListener(e -> {
            if (ruta_archivos == null) {
                JOptionPane.showMessageDialog(frame,
                        "Por favor seleccione una carpeta de destino primero",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            String[] options = {"Archivo", "Carpeta", "Cancelar"};
            int choice = JOptionPane.showOptionDialog(frame,
                    "¿Qué desea crear?",
                    "Crear nuevo",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options[0]);

            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
                return;
            }

            String name = JOptionPane.showInputDialog(frame,
                    "Ingrese el nombre:");
            if (name == null || name.trim().isEmpty()) {
                return;
            }

            DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            File parentDir;

            if (selectedNode == null || selectedNode == rootNode) {
                parentDir = new File(ruta_archivos);
            } else {
                parentDir = (File) selectedNode.getUserObject();
                if (!parentDir.isDirectory()) {
                    parentDir = parentDir.getParentFile();
                }
            }

            try {
                File newFile = new File(parentDir, name);
                if (choice == 0) {
                    if (newFile.createNewFile()) {
                        statusArea.append("Archivo creado: " + newFile.getAbsolutePath() + "\n");
                    } else {
                        JOptionPane.showMessageDialog(frame,
                                "No se pudo crear el archivo. ¿Ya existe?",
                                "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                } else {
                    if (newFile.mkdir()) {
                        statusArea.append("Carpeta creada: " + newFile.getAbsolutePath() + "\n");
                    } else {
                        JOptionPane.showMessageDialog(frame,
                                "No se pudo crear la carpeta. ¿Ya existe?",
                                "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                }
                updateFileTree();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(frame,
                        "Error al crear: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        // Listener para el botón de eliminación
        deleteButton.addActionListener(e -> {
            DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            if (selectedNode == null || selectedNode == rootNode) {
                JOptionPane.showMessageDialog(frame,
                        "Por favor seleccione un archivo o carpeta para eliminar",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            File fileToDelete = (File) selectedNode.getUserObject();
            if (fileToDelete.getAbsolutePath().equals(ruta_archivos)) {
                JOptionPane.showMessageDialog(frame,
                        "No se puede eliminar el directorio raíz actual",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            int confirm = JOptionPane.showConfirmDialog(frame,
                    "¿Está seguro que desea eliminar " + fileToDelete.getName() + "?",
                    "Confirmar eliminación",
                    JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                boolean success;
                if (fileToDelete.isDirectory()) {
                    success = deleteDirectory(fileToDelete);
                } else {
                    success = fileToDelete.delete();
                }

                if (success) {
                    statusArea.append("Eliminado: " + fileToDelete.getAbsolutePath() + "\n");
                    updateFileTree();
                } else {
                    JOptionPane.showMessageDialog(frame,
                            "No se pudo eliminar. ¿Está en uso?",
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        // Listener para el botón de renombrar
        renameButton.addActionListener(e -> {
            DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            if (selectedNode == null || selectedNode == rootNode) {
                JOptionPane.showMessageDialog(frame,
                        "Por favor seleccione un archivo o carpeta para renombrar",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            File fileToRename = (File) selectedNode.getUserObject();
            String newName = JOptionPane.showInputDialog(frame,
                    "Nuevo nombre:",
                    fileToRename.getName());

            if (newName == null || newName.trim().isEmpty() || newName.equals(fileToRename.getName())) {
                return;
            }

            File newFile = new File(fileToRename.getParentFile(), newName);
            if (fileToRename.renameTo(newFile)) {
                statusArea.append("Renombrado: " + fileToRename.getName() + " → " + newName + "\n");
                updateFileTree();
            } else {
                JOptionPane.showMessageDialog(frame,
                        "No se pudo renombrar. ¿El nuevo nombre ya existe?",
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        
        // Listener para el botón de enviar archivo
        sendButton.addActionListener(e -> {
            if (selectedFile == null) {
                JOptionPane.showMessageDialog(frame,
                        "Por favor seleccione un archivo primero",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            taskThreadPool.execute(() -> {
                try {
                    String dir = clientField.getText();
                    int pto = Integer.parseInt(portField.getText());

                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Conectando al cliente " + dir + ":" + pto + "...\n");
                    });

                    Socket cl = new Socket(dir, pto);

                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Conexión establecida. Enviando archivo...\n");
                    });

                    String nombre = selectedFile.getName();
                    String path = selectedFile.getAbsolutePath();
                    long tam = selectedFile.length();

                    DataOutputStream dos = new DataOutputStream(cl.getOutputStream());
                    DataInputStream dis = new DataInputStream(new FileInputStream(path));

                    dos.writeUTF(nombre);
                    dos.flush();
                    dos.writeLong(tam);
                    dos.flush();

                    long enviados = 0;
                    int l = 0;
                    byte[] b = new byte[3500];
                    while (enviados < tam) {
                        l = dis.read(b);
                        if (l == -1) break;
                        dos.write(b, 0, l);
                        dos.flush();
                        enviados += l;
                        final int porcentaje = (int) ((enviados * 100) / tam);

                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("\rProgreso: " + porcentaje + "%");
                        });
                    }

                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("\nArchivo enviado con éxito!\n");
                        updateFileTree();
                    });

                    dis.close();
                    dos.close();
                    cl.close();
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Error al enviar archivo: " + ex.getMessage() + "\n");
                    });
                    ex.printStackTrace();
                }
            });
        });

        frame.setVisible(true);
    }

    private static void startServer() {
        taskThreadPool.execute(() -> {
            try {
                int pto = 8000;
                serverSocket = new ServerSocket(pto);
                serverSocket.setReuseAddress(true);

                File f2 = new File(ruta_archivos);
                if (!f2.exists()) {
                    f2.mkdirs();
                    statusArea.append("Carpeta creada: " + ruta_archivos + "\n");
                }
                f2.setWritable(true);

                statusArea.append("Servidor iniciado en puerto " + pto + "\n");
                statusArea.append("Máximo de clientes simultáneos: " + MAX_CLIENTS + "\n");

                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = serverSocket.accept();
                    statusArea.append("Nuevo cliente conectado: " + 
                            clientSocket.getInetAddress() + "\n");

                    clientThreadPool.execute(() -> handleClient(clientSocket));
                }
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    statusArea.append("Error en servidor: " + e.getMessage() + "\n");
                }
            } finally {
                shutdownServer();
            }
        });
    }

    private static void handleClient(Socket clientSocket) {
        try {
            DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
            String nombre = dis.readUTF();
            long tam = dis.readLong();

            nombre = sanitizeFilename(nombre);

            updateStatus("Recibiendo archivo: " + nombre + " (" + tam + " bytes)");

            File receivedFile = new File(ruta_archivos + nombre);
            DataOutputStream dos = new DataOutputStream(new FileOutputStream(receivedFile));

            long recibidos = 0;
            byte[] buffer = new byte[3500];
            int bytesRead;
            
            while (recibidos < tam && (bytesRead = dis.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
                recibidos += bytesRead;
                final int progress = (int) ((recibidos * 100) / tam);
                updateStatus("\rProgreso: " + progress + "%");
            }

            updateStatus("\nArchivo recibido: " + nombre + "\n");
            SwingUtilities.invokeLater(() -> updateFileTree());

            dos.close();
            dis.close();
        } catch (Exception e) {
            updateStatus("Error con cliente: " + e.getMessage() + "\n");
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                // Ignorar error al cerrar
            }
        }
    }

    private static String sanitizeFilename(String filename) {
        return filename.replace("..", "").replace("/", "").replace("\\", "");
    }

    private static void updateStatus(String message) {
        SwingUtilities.invokeLater(() -> statusArea.append(message));
    }

    private static void shutdownServer() {
        try {
            clientThreadPool.shutdown();
            taskThreadPool.shutdown();
            
            if (!clientThreadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                clientThreadPool.shutdownNow();
            }
            if (!taskThreadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                taskThreadPool.shutdownNow();
            }
            
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            
            updateStatus("Servidor detenido correctamente\n");
        } catch (Exception e) {
            updateStatus("Error al detener servidor: " + e.getMessage() + "\n");
        }
    }

    private static boolean deleteDirectory(File directory) {
        File[] allContents = directory.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return directory.delete();
    }

    private static void updateFileTree() {
        rootNode.removeAllChildren();
        if (ruta_archivos != null && !ruta_archivos.isEmpty()) {
            File rootFile = new File(ruta_archivos);
            DefaultMutableTreeNode root = new DefaultMutableTreeNode(rootFile);
            rootNode.add(root);
            populateTree(root, rootFile);
        }
        treeModel.reload();
        expandAllNodes(fileTree, 0, fileTree.getRowCount());
    }

    private static void expandAllNodes(JTree tree, int startingIndex, int rowCount) {
        for (int i = startingIndex; i < rowCount; ++i) {
            tree.expandRow(i);
        }
        if (tree.getRowCount() != rowCount) {
            expandAllNodes(tree, rowCount, tree.getRowCount());
        }
    }

    private static void populateTree(DefaultMutableTreeNode node, File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child);
                    node.add(childNode);
                    if (child.isDirectory()) {
                        populateTree(childNode, child);
                    }
                }
            }
        }
    }
}