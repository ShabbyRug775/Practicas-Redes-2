package cliente;

import java.net.*;
import java.io.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.tree.*;
import javax.swing.event.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.*;
import java.nio.file.*;

public class Cliente {

    // Componentes de la interfaz gráfica
    private static JTextArea statusArea;
    private static JTree fileTree;
    private static JFrame frame;
    private static JTextField serverField;
    private static JTextField portField;
    private static JTextField clientReceptionPortField;

    // Variables de estado
    private static File selectedFile;
    private static DefaultMutableTreeNode rootNode;
    private static DefaultTreeModel treeModel;
    private static String currentRootPath;
    private static int receptionPort;
    
    // Configuración NIO
    private static Selector selector;
    private static ServerSocketChannel serverChannel;
    private static volatile boolean receptionActive = false;
    private static ExecutorService receptionThreadPool;
    private static final int MAX_RECEPTIONS = 5;
    private static final int BUFFER_SIZE = 3500;

    // Estado para la recepción de archivos
    static class FileReceptionState {
        String fileName;
        long fileSize;
        long bytesReceived;
        FileOutputStream fos;
        ByteBuffer buffer;
        boolean metadataReceived = false;

        public FileReceptionState(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        public void close() {
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (IOException e) {
                updateStatus("Error al cerrar archivo: " + e.getMessage() + "\n");
            }
        }
    }

    public static void main(String[] args) {
        // Configurar pool de hilos
        receptionThreadPool = Executors.newFixedThreadPool(MAX_RECEPTIONS);

        // Configurar interfaz gráfica
        initializeGUI();

        // Mostrar ventana
        frame.setVisible(true);
    }

    private static void initializeGUI() {
        frame = new JFrame("Cliente de Archivos (NIO)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1100, 600);
        frame.setLayout(new BorderLayout());

        // Panel superior de configuración
        JPanel serverPanel = new JPanel(new FlowLayout());
        
        // Campos de texto
        serverField = new JTextField("127.0.0.1", 15);
        portField = new JTextField("8000", 5);
        clientReceptionPortField = new JTextField("8002", 5);
        
        // Botones
        JButton loadFsButton = new JButton("Cargar Sistema de Archivos");
        JButton startClientReceptionButton = new JButton("Iniciar Recepción");
        JButton stopClientReceptionButton = new JButton("Detener Recepción");
        
        // Añadir componentes al panel
        serverPanel.add(new JLabel("Servidor:"));
        serverPanel.add(serverField);
        serverPanel.add(new JLabel("Puerto Servidor:"));
        serverPanel.add(portField);
        serverPanel.add(new JLabel("Puerto Cliente:"));
        serverPanel.add(clientReceptionPortField);
        serverPanel.add(loadFsButton);
        serverPanel.add(startClientReceptionButton);
        serverPanel.add(stopClientReceptionButton);
        
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        JSplitPane fileSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        // Configurar árbol de archivos
        rootNode = new DefaultMutableTreeNode("Sistema de Archivos");
        treeModel = new DefaultTreeModel(rootNode);
        fileTree = new JTree(treeModel);
        fileTree.setEditable(false);
        JScrollPane treeScroll = new JScrollPane(fileTree);

        // Panel de botones de acción
        JPanel infoPanel = new JPanel(new BorderLayout());
        JButton sendButton = new JButton("Enviar Archivo");
        JButton refreshButton = new JButton("Actualizar");
        JButton createButton = new JButton("Crear");
        JButton deleteButton = new JButton("Eliminar");
        JButton renameButton = new JButton("Renombrar");
        JPanel buttonPanel = new JPanel(new GridLayout(5, 1));
        buttonPanel.add(sendButton);
        buttonPanel.add(refreshButton);
        buttonPanel.add(createButton);
        buttonPanel.add(deleteButton);
        buttonPanel.add(renameButton);

        JLabel fileInfoLabel = new JLabel("Archivo seleccionado: Ninguno");
        infoPanel.add(fileInfoLabel, BorderLayout.NORTH);
        infoPanel.add(buttonPanel, BorderLayout.CENTER);

        // Configurar split panes
        fileSplitPane.setLeftComponent(treeScroll);
        fileSplitPane.setRightComponent(infoPanel);
        fileSplitPane.setDividerLocation(700);

        // Área de estado
        statusArea = new JTextArea();
        statusArea.setEditable(false);
        JScrollPane statusScroll = new JScrollPane(statusArea);

        mainSplitPane.setTopComponent(fileSplitPane);
        mainSplitPane.setBottomComponent(statusScroll);
        mainSplitPane.setDividerLocation(350);

        // Añadir componentes principales al frame
        frame.add(serverPanel, BorderLayout.NORTH);
        frame.add(mainSplitPane, BorderLayout.CENTER);

        // Listeners
        fileTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            if (node == null) return;

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

        loadFsButton.addActionListener(e -> loadFileSystem());
        refreshButton.addActionListener(e -> refreshFileSystem());
        createButton.addActionListener(e -> createFileOrDirectory());
        deleteButton.addActionListener(e -> deleteSelected());
        renameButton.addActionListener(e -> renameSelected());
        sendButton.addActionListener(e -> sendFile());
        startClientReceptionButton.addActionListener(e -> startReception());
        stopClientReceptionButton.addActionListener(e -> stopReception());
    }

    private static void loadFileSystem() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int returnVal = fileChooser.showOpenDialog(frame);
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            currentRootPath = fileChooser.getSelectedFile().getAbsolutePath();
            updateStatus("Directorio raíz seleccionado: " + currentRootPath + "\n");
            updateFileTree();
        }
    }

    private static void refreshFileSystem() {
        if (currentRootPath != null && !currentRootPath.isEmpty()) {
            updateFileTree();
            updateStatus("Contenido actualizado\n");
        } else {
            updateStatus("Error: No se ha seleccionado un directorio raíz\n");
        }
    }

    private static void createFileOrDirectory() {
        if (currentRootPath == null) {
            JOptionPane.showMessageDialog(frame, "Por favor seleccione un directorio raíz primero", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String[] options = {"Archivo", "Carpeta", "Cancelar"};
        int choice = JOptionPane.showOptionDialog(frame, "¿Qué desea crear?", "Crear nuevo",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);

        if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return;

        String name = JOptionPane.showInputDialog(frame, "Ingrese el nombre:");
        if (name == null || name.trim().isEmpty()) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
        File parentDir;

        if (selectedNode == null || selectedNode == rootNode) {
            parentDir = new File(currentRootPath);
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
                    updateStatus("Archivo creado: " + newFile.getAbsolutePath() + "\n");
                } else {
                    JOptionPane.showMessageDialog(frame, "No se pudo crear el archivo. ¿Ya existe?", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } else {
                if (newFile.mkdir()) {
                    updateStatus("Carpeta creada: " + newFile.getAbsolutePath() + "\n");
                } else {
                    JOptionPane.showMessageDialog(frame, "No se pudo crear la carpeta. ¿Ya existe?", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
            updateFileTree();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(frame, "Error al crear: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void deleteSelected() {
        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
        if (selectedNode == null || selectedNode == rootNode) {
            JOptionPane.showMessageDialog(frame, "Por favor seleccione un archivo o carpeta para eliminar", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File fileToDelete = (File) selectedNode.getUserObject();
        if (fileToDelete.getAbsolutePath().equals(currentRootPath)) {
            JOptionPane.showMessageDialog(frame, "No se puede eliminar el directorio raíz actual", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(frame, 
                "¿Está seguro que desea eliminar " + fileToDelete.getName() + "?", 
                "Confirmar eliminación", JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            boolean success;
            if (fileToDelete.isDirectory()) {
                success = deleteDirectory(fileToDelete);
            } else {
                success = fileToDelete.delete();
            }

            if (success) {
                updateStatus("Eliminado: " + fileToDelete.getAbsolutePath() + "\n");
                updateFileTree();
            } else {
                JOptionPane.showMessageDialog(frame, "No se pudo eliminar. ¿Está en uso?", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static void renameSelected() {
        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
        if (selectedNode == null || selectedNode == rootNode) {
            JOptionPane.showMessageDialog(frame, "Por favor seleccione un archivo o carpeta para renombrar", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File fileToRename = (File) selectedNode.getUserObject();
        String newName = JOptionPane.showInputDialog(frame, "Nuevo nombre:", fileToRename.getName());

        if (newName == null || newName.trim().isEmpty() || newName.equals(fileToRename.getName())) {
            return;
        }

        File newFile = new File(fileToRename.getParentFile(), newName);
        if (fileToRename.renameTo(newFile)) {
            updateStatus("Renombrado: " + fileToRename.getName() + " → " + newName + "\n");
            updateFileTree();
        } else {
            JOptionPane.showMessageDialog(frame, "No se pudo renombrar. ¿El nuevo nombre ya existe?", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void sendFile() {
        if (selectedFile == null) {
            JOptionPane.showMessageDialog(frame, "Por favor seleccione un archivo primero", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        receptionThreadPool.execute(() -> {
            try {
                String serverAddress = serverField.getText();
                int serverPort = Integer.parseInt(portField.getText());

                if (serverPort == receptionPort) {
                    updateStatus("Error: No se puede enviar al puerto de recepción del propio cliente.\n");
                    return;
                }

                updateStatus("Conectando al servidor " + serverAddress + ":" + serverPort + "...\n");

                try (Socket socket = new Socket(serverAddress, serverPort);
                     DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                     FileInputStream fis = new FileInputStream(selectedFile)) {

                    updateStatus("Conexión establecida. Enviando archivo...\n");

                    // Enviar metadatos
                    dos.writeUTF(selectedFile.getName());
                    dos.writeLong(selectedFile.length());
                    dos.flush();

                    // Enviar datos del archivo
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    long totalSent = 0;
                    long fileSize = selectedFile.length();

                    while ((bytesRead = fis.read(buffer)) != -1) {
                        dos.write(buffer, 0, bytesRead);
                        totalSent += bytesRead;
                        int progress = (int) ((totalSent * 100) / fileSize);
                        updateStatus("\rProgreso: " + progress + "%");
                    }

                    updateStatus("\nArchivo enviado con éxito!\n");
                    SwingUtilities.invokeLater(() -> updateFileTree());
                }
            } catch (NumberFormatException e) {
                updateStatus("Error: Puerto inválido\n");
            } catch (Exception e) {
                updateStatus("Error al enviar archivo: " + e.getMessage() + "\n");
            }
        });
    }

    private static void startReception() {
        try {
            receptionPort = Integer.parseInt(clientReceptionPortField.getText());
            startNIOReception();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(frame, "Por favor, ingrese un número de puerto válido.", "Error de Puerto", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void stopReception() {
        stopNIOReception();
    }

    private static void startNIOReception() {
        if (receptionActive) {
            updateStatus("La recepción ya está activa en el puerto " + receptionPort + "\n");
            return;
        }

        receptionThreadPool.execute(() -> {
            try {
                serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
                serverChannel.socket().setReuseAddress(true);
                serverChannel.socket().bind(new InetSocketAddress(receptionPort));
                
                selector = Selector.open();
                serverChannel.register(selector, SelectionKey.OP_ACCEPT);
                
                receptionActive = true;
                updateStatus("Cliente NIO listo para recibir archivos en puerto " + receptionPort + "\n");
                updateStatus("Máximo de transferencias simultáneas: " + MAX_RECEPTIONS + "\n");

                while (receptionActive && selector.isOpen()) {
                    selector.select(1000); // Timeout de 1 segundo
                    
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();

                        try {
                            if (key.isAcceptable()) {
                                acceptNIOConnection(key);
                            } else if (key.isReadable()) {
                                handleNIOFileTransfer(key);
                            }
                        } catch (IOException ex) {
                            key.cancel();
                            try {
                                key.channel().close();
                            } catch (IOException e) {
                                updateStatus("Error al cerrar conexión: " + e.getMessage() + "\n");
                            }
                            updateStatus("Error en conexión: " + ex.getMessage() + "\n");
                        }
                    }
                }
            } catch (BindException e) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(null, 
                        "El puerto " + receptionPort + " ya está en uso. Elija otro puerto.", 
                        "Error de Puerto", JOptionPane.ERROR_MESSAGE);
                    updateStatus("Error: Puerto " + receptionPort + " en uso. " + e.getMessage() + "\n");
                });
            } catch (IOException e) {
                if (!(e.getClass().isAssignableFrom(ClosedSelectorException.class) || e.getClass().isAssignableFrom(ClosedChannelException.class))) {
                    updateStatus("Error en recepción NIO: " + e.getMessage() + "\n");
                }
            } finally {
                stopNIOReception();
            }
        });
    }

    private static void acceptNIOConnection(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
        clientChannel.configureBlocking(false);
        
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        clientChannel.register(selector, SelectionKey.OP_READ, new FileReceptionState(buffer));
        
        InetSocketAddress remoteAddress = (InetSocketAddress) clientChannel.getRemoteAddress();
        updateStatus("Conexión entrante desde " + remoteAddress.getHostString() + ":" + remoteAddress.getPort() + "\n");
    }

    private static void handleNIOFileTransfer(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        FileReceptionState state = (FileReceptionState) key.attachment();
        
        if (!state.metadataReceived) {
            // Primero recibir metadatos (nombre y tamaño del archivo)
            ByteBuffer metadataBuffer = ByteBuffer.allocate(Long.BYTES + (Integer.BYTES + 255));
            
            int bytesRead = clientChannel.read(metadataBuffer);
            if (bytesRead == -1) {
                throw new IOException("Conexión cerrada por el cliente");
            }
            
            if (bytesRead > 0 && metadataBuffer.position() >= metadataBuffer.capacity()) {
                metadataBuffer.flip();
                
                try {
                    int nameLength = metadataBuffer.getInt();
                    byte[] nameBytes = new byte[nameLength];
                    metadataBuffer.get(nameBytes);
                    state.fileName = new String(nameBytes, StandardCharsets.UTF_8);
                    state.fileSize = metadataBuffer.getLong();
                    
                    state.fileName = sanitizeFilename(state.fileName);
                    
                    if (currentRootPath == null) {
                        updateStatus("Error: No se ha seleccionado directorio de destino\n");
                        key.cancel();
                        clientChannel.close();
                        return;
                    }
                    
                    // Crear directorios padres si no existen
                    Path destinationPath = Paths.get(currentRootPath, state.fileName);
                    Files.createDirectories(destinationPath.getParent());
                    
                    state.fos = new FileOutputStream(destinationPath.toFile());
                    state.metadataReceived = true;
                    
                    // Procesar cualquier dato adicional que vino con los metadatos
                    if (metadataBuffer.hasRemaining()) {
                        int remaining = metadataBuffer.remaining();
                        byte[] extraData = new byte[remaining];
                        metadataBuffer.get(extraData);
                        state.fos.write(extraData);
                        state.bytesReceived += remaining;
                    }
                    
                    updateStatus("Recibiendo archivo: " + state.fileName + " (" + state.fileSize + " bytes)\n");
                } catch (BufferUnderflowException e) {
                    throw new IOException("Formato de metadatos inválido");
                } catch (IOException e) {
                    throw new IOException("Error al crear archivo: " + e.getMessage());
                }
            }
        } else {
            // Recibir datos del archivo
            state.buffer.clear();
            int bytesRead = clientChannel.read(state.buffer);
            
            if (bytesRead == -1) {
                throw new IOException("Conexión cerrada por el cliente");
            }
            
            if (bytesRead > 0) {
                state.buffer.flip();
                state.fos.write(state.buffer.array(), 0, bytesRead);
                state.bytesReceived += bytesRead;
                
                final int porcentaje = (int) ((state.bytesReceived * 100) / state.fileSize);
                updateStatus("\rProgreso: " + porcentaje + "%");
                
                if (state.bytesReceived >= state.fileSize) {
                    updateStatus("\nArchivo recibido: " + state.fileName + "\n");
                    SwingUtilities.invokeLater(() -> updateFileTree());
                    key.cancel();
                    clientChannel.close();
                    state.close();
                }
            }
        }
    }

    private static void stopNIOReception() {
        if (!receptionActive) return;
        
        receptionActive = false;
        
        try {
            if (selector != null && selector.isOpen()) {
                selector.wakeup();
                selector.close();
            }
            
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
            }
            
            updateStatus("Recepción NIO detenida en puerto " + receptionPort + "\n");
        } catch (IOException e) {
            updateStatus("Error al detener recepción NIO: " + e.getMessage() + "\n");
        }
    }

    private static String sanitizeFilename(String filename) {
        return filename.replace("..", "").replace("/", "").replace("\\", "");
    }

    private static void updateStatus(String message) {
        SwingUtilities.invokeLater(() -> statusArea.append(message));
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
        if (currentRootPath != null && !currentRootPath.isEmpty()) {
            File rootFile = new File(currentRootPath);
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