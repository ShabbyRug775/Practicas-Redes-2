package servidor;

import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.io.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import javax.swing.tree.*;
import javax.swing.event.*;
import javax.swing.filechooser.*;

public class servidor {
    
    // Componentes y variables existentes...
    private static final int BUF_SIZE = 65507;
    private static final int WINDOW_SIZE = 4; // Tamaño de ventana para Go-Back-N
    private static final int FRAG_SIZE = 1024; // Tamaño de fragmento
    private static final int TIMEOUT_MS = 1000; // Timeout para retransmisiones
    
    private static final int INITIAL_TIMEOUT = 1000;
    private static int currentTimeout = INITIAL_TIMEOUT;

    // Componentes de la interfaz gráfica y variables de estado
    private static JTextArea statusArea;            // Área de texto para mostrar el estado del servidor
    private static File selectedFile;               // Archivo seleccionado para enviar
    private static String ruta_archivos;            // Ruta donde se almacenarán los archivos recibidos
    //private static ServerSocket serverSocket;       // Socket del servidor para aceptar conexiones
    private static DefaultMutableTreeNode rootNode; // Nodo raíz para el árbol de archivos
    private static JTree fileTree;                  // Componente JTree para mostrar la estructura de archivos
    private static DefaultTreeModel treeModel;      // Modelo de datos para el JTree
    
    private static final int RECEPTION_PORT = 8000;         // Puerto de recepcion

    public static void main(String[] args) {
        // Configuración de la ventana principal
        JFrame frame = new JFrame("Servidor de Archivos");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(700, 600);
        frame.setLayout(new BorderLayout());
        
        // Panel superior para configuración del servidor
        JPanel clientPanel = new JPanel(new FlowLayout());
        JLabel clientLabel = new JLabel("Cliente:");
        JTextField clientField = new JTextField("127.0.0.1", 15);   // Campo para la IP del cliente
        JLabel portLabel = new JLabel("Puerto:");
        JTextField portField = new JTextField("8001", 5);           // Campo para el puerto diferente al del servidor
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

            // Actualiza la información del archivo seleccionado
            Object nodeInfo = node.getUserObject();
            if (nodeInfo instanceof File) {
                File file = (File) nodeInfo;
                if (!file.isDirectory()) {
                    selectedFile = file;
                    fileInfoLabel.setText("Archivo seleccionado: " + file.getName()
                            + " (" + file.length() + " bytes)");
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
                updateFileTree(); // Actualiza el árbol con la nueva ruta
                statusArea.append("Carpeta de destino seleccionada: " + ruta_archivos + "\n");
                startServer(); // Inicia el servidor después de seleccionar la carpeta
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

            // Diálogo para seleccionar tipo de elemento a crear
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

            // Solicita nombre para el nuevo elemento
            String name = JOptionPane.showInputDialog(frame,
                    "Ingrese el nombre:");
            if (name == null || name.trim().isEmpty()) {
                return;
            }

            // Determina el directorio padre donde se creará el elemento
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
                if (choice == 0) { // Crear archivo
                    if (newFile.createNewFile()) {
                        statusArea.append("Archivo creado: " + newFile.getAbsolutePath() + "\n");
                    } else {
                        JOptionPane.showMessageDialog(frame,
                                "No se pudo crear el archivo. ¿Ya existe?",
                                "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                } else { // Crear carpeta
                    if (newFile.mkdir()) {
                        statusArea.append("Carpeta creada: " + newFile.getAbsolutePath() + "\n");
                    } else {
                        JOptionPane.showMessageDialog(frame,
                                "No se pudo crear la carpeta. ¿Ya existe?",
                                "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                }
                updateFileTree(); // Actualiza el árbol después de la creación
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
            // Evita eliminar el directorio raíz
            if (fileToDelete.getAbsolutePath().equals(ruta_archivos)) {
                JOptionPane.showMessageDialog(frame,
                        "No se puede eliminar el directorio raíz actual",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Confirmación antes de eliminar
            int confirm = JOptionPane.showConfirmDialog(frame,
                    "¿Está seguro que desea eliminar " + fileToDelete.getName() + "?",
                    "Confirmar eliminación",
                    JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                boolean success;
                if (fileToDelete.isDirectory()) {
                    success = deleteDirectory(fileToDelete); // Elimina directorio recursivamente
                } else {
                    success = fileToDelete.delete(); // Elimina archivo
                }

                if (success) {
                    statusArea.append("Eliminado: " + fileToDelete.getAbsolutePath() + "\n");
                    updateFileTree(); // Actualiza el árbol después de eliminar
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
            // Solicita nuevo nombre
            String newName = JOptionPane.showInputDialog(frame,
                    "Nuevo nombre:",
                    fileToRename.getName());

            if (newName == null || newName.trim().isEmpty() || newName.equals(fileToRename.getName())) {
                return;
            }

            // Intenta renombrar el archivo/directorio
            File newFile = new File(fileToRename.getParentFile(), newName);
            if (fileToRename.renameTo(newFile)) {
                statusArea.append("Renombrado: " + fileToRename.getName() + " → " + newName + "\n");
                updateFileTree(); // Actualiza el árbol después de renombrar
            } else {
                JOptionPane.showMessageDialog(frame,
                        "No se pudo renombrar. ¿El nuevo nombre ya existe?",
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        
        // Listener para el botón de enviar archivo
        sendButton.addActionListener(e -> {
            if (selectedFile == null) {
                JOptionPane.showMessageDialog(frame, "Por favor seleccione un archivo primero", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            new Thread(() -> {
                try {
                    String dir = clientField.getText();
                    int pto = Integer.parseInt(portField.getText());

                    if (pto == RECEPTION_PORT) {
                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("Error: No se puede enviar al puerto de recepción\n");
                        });
                        return;
                    }

                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Conectando al servidor " + dir + ":" + pto + "...\n");
                    });

                    DatagramSocket socket = new DatagramSocket();
                    socket.setSoTimeout(TIMEOUT_MS);
                    InetAddress serverAddress = InetAddress.getByName(dir);

                    // 1. Handshake mejorado con Go-Back-N
                    long fileSize = selectedFile.length();
                    int totalPackets = (int) Math.ceil((double) fileSize / FRAG_SIZE);

                    // Modificar el handshake para incluir información de tamaño
                    String hs = String.join("|",
                        "HANDSHAKE",
                        String.valueOf(WINDOW_SIZE),
                        String.valueOf(FRAG_SIZE),
                        selectedFile.getName(),
                        String.valueOf(totalPackets),
                        String.valueOf(selectedFile.length()) // Añadir tamaño total
                    );

                    // Verificación adicional (opcional)
                    if (hs.split("\\|").length != 6) {
                        throw new IllegalStateException("Handshake mal formado");
                    }

                    DatagramPacket hsPacket = new DatagramPacket(
                        hs.getBytes(), hs.length(), serverAddress, pto);
                    socket.send(hsPacket);

                    // Esperar confirmación de handshake
                    byte[] ackBuf = new byte[2];
                    DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length);
                    socket.receive(ackPacket);

                    if (!new String(ackPacket.getData(), 0, ackPacket.getLength()).equals("OK")) {
                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("Error en handshake con el cliente\n");
                        });
                        return;
                    }

                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Conexión establecida. Enviando archivo con Go-Back-N...\n");
                    });

                    // Preparar todos los paquetes
                    java.util.List<byte[]> packets = new ArrayList<>();
                    try (FileInputStream fis = new FileInputStream(selectedFile)) {
                        byte[] buffer = new byte[FRAG_SIZE];
                        int read;
                        int seq = 0;

                        while ((read = fis.read(buffer)) != -1) {
                            byte[] packetData = new byte[4 + read];
                            packetData[0] = (byte)(seq >> 8);
                            packetData[1] = (byte)(seq);
                            packetData[2] = (byte)(totalPackets >> 8);
                            packetData[3] = (byte)(totalPackets);
                            System.arraycopy(buffer, 0, packetData, 4, read);
                            packets.add(packetData);
                            seq++;
                        }
                    }

                    // Envío con ventana deslizante
                    int base = 0;
                    while (base < totalPackets) {
                        int windowEnd = Math.min(base + WINDOW_SIZE, totalPackets);

                        // Enviar toda la ventana
                        for (int i = base; i < windowEnd; i++) {
                            DatagramPacket packet = new DatagramPacket(
                                packets.get(i), packets.get(i).length, serverAddress, pto);
                            socket.send(packet);
                            final int j = i;
                            SwingUtilities.invokeLater(() -> {
                                statusArea.append("→ Enviado paquete #" + j + "\n");
                            });
                        }

                        // Esperar ACKs
                        try {
                            while (base < windowEnd) {
                                socket.receive(ackPacket);
                                int ackNum = ((ackPacket.getData()[0]&0xFF)<<8)|(ackPacket.getData()[1]&0xFF);
                                SwingUtilities.invokeLater(() -> {
                                    statusArea.append("✓ ACK recibido: " + ackNum + "\n");
                                });

                                if (ackNum >= base) {
                                    base = ackNum + 1;
                                    int progress = (int)((base * 100.0) / totalPackets);
                                    SwingUtilities.invokeLater(() -> {
                                        statusArea.append("\rProgreso: " + progress + "%");
                                    });
                                }
                            }
                        } catch (SocketTimeoutException ex) {
                            final int baseF = base;
                            SwingUtilities.invokeLater(() -> {
                                statusArea.append("Timeout. Reenviando desde paquete #" + baseF + "\n");
                            });
                        }
                    }

                    // Enviar señal de fin
                    byte[] endSignal = "END".getBytes();
                    DatagramPacket endPacket = new DatagramPacket(
                        endSignal, endSignal.length, serverAddress, pto);
                    socket.send(endPacket);

                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("\nArchivo enviado con éxito!\n");
                    });

                    socket.close();
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Error al enviar archivo: " + ex.getMessage() + "\n");
                    });
                    ex.printStackTrace();
                }
            }).start();
        });

        frame.setVisible(true); // Muestra la ventana
    }

    // Método auxiliar para eliminar directorios recursivamente
    private static boolean deleteDirectory(File directory) {
        File[] allContents = directory.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file); // Elimina recursivamente el contenido
            }
        }
        return directory.delete(); // Elimina el directorio vacío
    }

    // Actualiza el árbol de archivos con el contenido del directorio actual
    private static void updateFileTree() {
        rootNode.removeAllChildren(); // Limpia el árbol
        if (ruta_archivos != null && !ruta_archivos.isEmpty()) {
            File rootFile = new File(ruta_archivos);
            DefaultMutableTreeNode root = new DefaultMutableTreeNode(rootFile);
            rootNode.add(root);
            populateTree(root, rootFile); // Puebla el árbol con el contenido
        }
        treeModel.reload(); // Recarga el modelo
        expandAllNodes(fileTree, 0, fileTree.getRowCount()); // Expande todos los nodos
    }

    // Expande todos los nodos del árbol recursivamente
    private static void expandAllNodes(JTree tree, int startingIndex, int rowCount) {
        for (int i = startingIndex; i < rowCount; ++i) {
            tree.expandRow(i);
        }
        if (tree.getRowCount() != rowCount) {
            expandAllNodes(tree, rowCount, tree.getRowCount()); // Expande nodos nuevos
        }
    }

    // Puebla el árbol con el contenido del directorio
    private static void populateTree(DefaultMutableTreeNode node, File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child);
                    node.add(childNode);
                    if (child.isDirectory()) {
                        populateTree(childNode, child); // Recursión para subdirectorios
                    }
                }
            }
        }
    }

    // Inicia el servidor en un hilo separado
    private static void startServer() {
        new Thread(() -> {
            try {
                int pto = 8000;
                DatagramSocket socket = new DatagramSocket(pto);
                socket.setReuseAddress(true);

                // Asegurar directorio de destino
                File f2 = new File(ruta_archivos);
                if (!f2.exists()) {
                    f2.mkdirs();
                    statusArea.append("Carpeta creada: " + ruta_archivos + "\n");
                }
                f2.setWritable(true);

                statusArea.append("Servidor UDP (Go-Back-N) iniciado en puerto " + pto + "\n");

                while (true) {
                    byte[] receiveBuffer = new byte[BUF_SIZE];
                    DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    
                    // 1. Recibir handshake
                    socket.receive(packet);
                    String hs = new String(packet.getData(), 0, packet.getLength());
                    
                    String[] parts = hs.split("\\|");
                    
                    // Validación completa del handshake
                    if (parts.length < 6 || !parts[0].equals("HANDSHAKE")) {
                        statusArea.append("Error: Handshake inválido. Formato esperado: HANDSHAKE|window|frag|filename|packets|size\n");
                        continue; // Saltar este paquete malformado
                    }

                    try {
                        int windowSize = Integer.parseInt(parts[1]);
                        int fragSize = Integer.parseInt(parts[2]);
                        String fileName = parts[3];
                        int totalPackets = Integer.parseInt(parts[4]);
                        long fileSize = Long.parseLong(parts[5]); // Nuevo campo de tamaño
                        
                        if (fileSize == 0) {
                            // Crear archivo vacío
                            File emptyFile = new File(ruta_archivos + parts[3]);
                            emptyFile.createNewFile();

                            // Enviar confirmación especial para archivo vacío
                            byte[] emptyAck = "EMPTY_FILE".getBytes();
                            socket.send(new DatagramPacket(emptyAck, emptyAck.length, 
                                      packet.getAddress(), packet.getPort()));

                            SwingUtilities.invokeLater(() -> {
                                statusArea.append("Archivo vacío recibido: " + parts[3] + "\n");
                                updateFileTree();
                            });
                            continue;
                        }
                        
                        // Limpieza del nombre de archivo
                        fileName = fileName.replace("..", "").replace("/", "").replace("\\", "");

                        // Enviar ACK de handshake
                        byte[] ackData = "OK".getBytes();
                        DatagramPacket ackPacket = new DatagramPacket(
                            ackData, ackData.length, packet.getAddress(), packet.getPort());
                        socket.send(ackPacket);

                        // Preparar para recepción
                        File outputFile = new File(ruta_archivos + fileName);
                        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                            int expectedSeq = 0;

                            while (expectedSeq < totalPackets) {
                                socket.receive(packet);

                                // Verificar si es paquete final
                                String dataStr = new String(packet.getData(), 0, packet.getLength());
                                if ("END".equals(dataStr.trim())) {
                                    break;
                                }

                                // Procesar paquete normal
                                byte[] data = packet.getData();
                                int seq = ((data[0]&0xFF)<<8)|(data[1]&0xFF);
                                int tot = ((data[2]&0xFF)<<8)|(data[3]&0xFF);
                                int payloadLen = packet.getLength() - 4;

                                if (seq == expectedSeq) {
                                    fos.write(data, 4, payloadLen);
                                    expectedSeq++;
                                }

                                // Enviar ACK acumulativo
                                ackData = new byte[2];
                                ackData[0] = (byte)(expectedSeq-1 >> 8);
                                ackData[1] = (byte)(expectedSeq-1);
                                ackPacket = new DatagramPacket(
                                    ackData, ackData.length, packet.getAddress(), packet.getPort());
                                socket.send(ackPacket);

                                // Actualizar progreso
                                final int progress = (int)((expectedSeq * 100.0) / totalPackets);
                                final String currentFileName = fileName;
                                SwingUtilities.invokeLater(() -> {
                                    statusArea.append("\rRecibiendo " + currentFileName + ": " + progress + "%");
                                });
                            }
                            final String fnf = fileName;
                            SwingUtilities.invokeLater(() -> {
                                statusArea.append("\nArchivo recibido: " + fnf + "\n");
                                updateFileTree();
                            });
                        } catch (IOException ex) {
                            SwingUtilities.invokeLater(() -> {
                                statusArea.append("Error al guardar archivo: " + ex.getMessage() + "\n");
                            });
                        }
                    } catch ( NumberFormatException e) {
                        statusArea.append("Error: Formato de handshake inválido\n");
                        continue; // Saltar este paquete malformado
                    }
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusArea.append("Error en servidor: " + e.getMessage() + "\n");
                });
                e.printStackTrace();
            }
        }).start();
    }
}