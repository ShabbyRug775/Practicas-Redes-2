package cliente;

import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.io.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.ArrayList;
import javax.swing.tree.*;
import javax.swing.event.*;

public class cliente {
    
    // Componentes y variables existentes...
    private static final int WINDOW_SIZE = 4; // Tamaño de ventana para Go-Back-N
    private static final int FRAG_SIZE = 1024; // Tamaño de fragmento
    private static final int TIMEOUT_MS = 1000; // Timeout para retransmisiones

    // Componentes de la interfaz gráfica y variables de estado
    private static JTextArea statusArea;                    // Área de texto para mostrar el estado del servidor
    private static File selectedFile;                       // Archivo seleccionado para enviar
    private static DefaultMutableTreeNode rootNode;         // Nodo raíz para el árbol de archivos
    private static JTree fileTree;                          // Componente JTree para mostrar la estructura de archivos
    private static DefaultTreeModel treeModel;              // Nodo raíz para el árbol de archivos
    private static String currentRootPath;                  // Ruta donde se almacenarán los archivos recibidos
    //private static ServerSocket receptionSocket;            // Socket del servidor para aceptar conexiones
    private static final int RECEPTION_PORT = 8001;         // Puerto de recepcion

    public static void main(String[] args) {
        // Configuración de la ventana principal
        JFrame frame = new JFrame("Cliente de Archivos");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(700, 600);
        frame.setLayout(new BorderLayout());

        // Panel superior para configuración del servidor
        JPanel serverPanel = new JPanel(new FlowLayout());
        JLabel serverLabel = new JLabel("Servidor:");
        JTextField serverField = new JTextField("127.0.0.1", 15);   // Campo para la IP del servidor
        JLabel portLabel = new JLabel("Puerto:");
        JTextField portField = new JTextField("8000", 5);           // Campo para el puerto de conexion
        serverPanel.add(serverLabel);
        serverPanel.add(serverField);
        serverPanel.add(portLabel);
        serverPanel.add(portField);

        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        JSplitPane fileSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        rootNode = new DefaultMutableTreeNode("Sistema de Archivos");
        treeModel = new DefaultTreeModel(rootNode);
        fileTree = new JTree(treeModel);
        fileTree.setEditable(false);
        JScrollPane treeScroll = new JScrollPane(fileTree);

        // Panel de botones de acción
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
        serverPanel.add(loadFsButton);

        frame.add(serverPanel, BorderLayout.NORTH);
        frame.add(mainSplitPane, BorderLayout.CENTER);

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

        loadFsButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int returnVal = fileChooser.showOpenDialog(frame);
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                currentRootPath = fileChooser.getSelectedFile().getAbsolutePath();
                statusArea.append("Directorio raíz seleccionado: " + currentRootPath + "\n");
                updateFileTree();
            }
        });

        refreshButton.addActionListener(e -> {
            if (currentRootPath != null && !currentRootPath.isEmpty()) {
                updateFileTree();
                statusArea.append("Contenido actualizado\n");
            }
        });

        createButton.addActionListener(e -> {
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
                        statusArea.append("Archivo creado: " + newFile.getAbsolutePath() + "\n");
                    } else {
                        JOptionPane.showMessageDialog(frame, "No se pudo crear el archivo. ¿Ya existe?", "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                } else {
                    if (newFile.mkdir()) {
                        statusArea.append("Carpeta creada: " + newFile.getAbsolutePath() + "\n");
                    } else {
                        JOptionPane.showMessageDialog(frame, "No se pudo crear la carpeta. ¿Ya existe?", "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                }
                updateFileTree();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(frame, "Error al crear: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        deleteButton.addActionListener(e -> {
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

            int confirm = JOptionPane.showConfirmDialog(frame, "¿Está seguro que desea eliminar " + fileToDelete.getName() + "?", "Confirmar eliminación", JOptionPane.YES_NO_OPTION);

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
                    JOptionPane.showMessageDialog(frame, "No se pudo eliminar. ¿Está en uso?", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        // Listener para el botón de renombrar
        renameButton.addActionListener(e -> {
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
                statusArea.append("Renombrado: " + fileToRename.getName() + " → " + newName + "\n");
                updateFileTree();
            } else {
                JOptionPane.showMessageDialog(frame, "No se pudo renombrar. ¿El nuevo nombre ya existe?", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        sendButton.addActionListener(e -> {
            if (selectedFile == null) {
                JOptionPane.showMessageDialog(frame, "Por favor seleccione un archivo primero", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            new Thread(() -> {
                try {
                    String dir = serverField.getText();
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
                            statusArea.append("Error en handshake con el servidor\n");
                        });
                        return;
                    }

                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Conexión establecida. Enviando archivo con Go-Back-N...\n");
                    });

                    // Preparar todos los paquetes
                    List<byte[]> packets = new ArrayList<>();
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


        // Iniciar recepción de archivos
        startFileReception();
        frame.setVisible(true);
    }

    private static void startFileReception() {
        new Thread(() -> {
            try {
                DatagramSocket receptionSocket = new DatagramSocket(RECEPTION_PORT);
                statusArea.append("Cliente listo para recibir archivos UDP en puerto " + RECEPTION_PORT + "\n");

                while (true) {
                    // Buffer para recibir los datos
                    byte[] receiveBuffer = new byte[65507];
                    DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);

                    // 1. Recibir handshake (mismo formato que el servidor)
                    receptionSocket.receive(packet);
                    String hs = new String(packet.getData(), 0, packet.getLength());

                    String[] parts = hs.split("\\|");

                    // Validación completa del handshake igual que en el servidor
                    if (parts.length < 6 || !parts[0].equals("HANDSHAKE")) {
                        statusArea.append("Error: Handshake inválido. Formato esperado: HANDSHAKE|window|frag|filename|packets|size\n");
                        continue;
                    }

                    try {
                        int windowSize = Integer.parseInt(parts[1]);
                        int fragSize = Integer.parseInt(parts[2]);
                        String fileName = parts[3];
                        int totalPackets = Integer.parseInt(parts[4]);
                        long fileSize = Long.parseLong(parts[5]);

                        // Limpieza del nombre de archivo
                        fileName = fileName.replace("..", "").replace("/", "").replace("\\", "");

                        // Enviar ACK de handshake (igual que el servidor)
                        byte[] ackData = "OK".getBytes();
                        DatagramPacket ackPacket = new DatagramPacket(
                            ackData, ackData.length, packet.getAddress(), packet.getPort());
                        receptionSocket.send(ackPacket);

                        // Manejo de archivo vacío
                        if (fileSize == 0) {
                            new File(currentRootPath + fileName).createNewFile();
                            final String name_final = fileName;
                            SwingUtilities.invokeLater(() -> {
                                statusArea.append("Archivo vacío recibido: " + name_final + "\n");
                                updateFileTree();
                            });
                            continue;
                        }

                        // Preparar archivo de destino
                        File receivedFile = new File(currentRootPath, fileName);

                        // Crear flujo de salida para guardar el archivo
                        try(FileOutputStream fos = new FileOutputStream(receivedFile)) {
                            int expectedSeq = 0;
                            long receivedBytes = 0;
                            int retries = 0;
                            final int MAX_RETRIES = 5;

                            while (expectedSeq < totalPackets && retries < MAX_RETRIES) {
                                receptionSocket.receive(packet);

                                // Verificar si es paquete final
                                if (packet.getLength() == 3 && 
                                    new String(packet.getData(), 0, 3).equals("END")) {
                                    break;
                                }

                                byte[] data = packet.getData();
                                int seq = ((data[0]&0xFF)<<8)|(data[1]&0xFF);
                                int tot = ((data[2]&0xFF)<<8)|(data[3]&0xFF);
                                int payloadLen = packet.getLength() - 4;

                                if (seq == expectedSeq) {
                                    fos.write(data, 4, payloadLen);
                                    receivedBytes += payloadLen;
                                    expectedSeq++;
                                    retries = 0; // Resetear contador de reintentos

                                    // Enviar ACK
                                    ackData = new byte[2];
                                    ackData[0] = (byte)(expectedSeq-1 >> 8);
                                    ackData[1] = (byte)(expectedSeq-1);
                                    ackPacket = new DatagramPacket(ackData, ackData.length, 
                                                                packet.getAddress(), packet.getPort());
                                    receptionSocket.send(ackPacket);

                                    // Actualizar progreso
                                    final int progress = (int)((receivedBytes * 100.0) / fileSize);
                                    final String fnf = fileName;
                                    SwingUtilities.invokeLater(() -> {
                                        statusArea.append("\rRecibiendo " + fnf + ": " + progress + "%");
                                    });
                                } else if (seq < expectedSeq) {
                                    // Reenviar ACK para paquetes ya recibidos
                                    ackData = new byte[2];
                                    ackData[0] = (byte)(seq >> 8);
                                    ackData[1] = (byte)(seq);
                                    ackPacket = new DatagramPacket(ackData, ackData.length, 
                                                                packet.getAddress(), packet.getPort());
                                    receptionSocket.send(ackPacket);
                                } else {
                                    retries++;
                                }
                            }
                            
                            // Después de recibir el archivo
                            File received = new File(currentRootPath, fileName);
                            if (received.length() == fileSize) {
                                SwingUtilities.invokeLater(() -> {
                                    statusArea.append("\nArchivo recibido correctamente. Tamaño verificado: " + 
                                                    received.length() + " bytes\n");
                                });
                            } else {
                                SwingUtilities.invokeLater(() -> {
                                    statusArea.append("\nERROR: Tamaño incorrecto. Esperado: " + fileSize + 
                                                    " Recibido: " + received.length() + " bytes\n");
                                });
                                received.delete(); // Eliminar archivo incompleto
                            }
                            updateFileTree();

                            if (retries >= MAX_RETRIES) {
                                SwingUtilities.invokeLater(() -> {
                                    statusArea.append("\nERROR: Demasiados reintentos. Transferencia incompleta.\n");
                                });
                            }
                        }
                    } catch (NumberFormatException e) {
                        statusArea.append("Error: Formato de handshake inválido\n");
                        continue;
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("Error en recepción UDP: " + e.getMessage() + "\n");
                        });
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusArea.append("Error en recepción UDP: " + e.getMessage() + "\n");
                });
                e.printStackTrace();
            }
        }).start();
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