package cliente;

import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.io.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.tree.*;
import javax.swing.event.*;

public class cliente {

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
                    String dir = serverField.getText();                 // IP del puerto
                    int pto = Integer.parseInt(portField.getText());    // IP del servidor

                    if (pto == RECEPTION_PORT) {
                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("Error: No se puede enviar al puerto de recepción\n");
                        });
                        return;
                    }

                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Conectando al servidor " + dir + ":" + pto + "...\n");
                    });

                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Conexión establecida. Enviando archivo...\n");
                    });

                    DatagramSocket socket = new DatagramSocket();
                    InetAddress serverAddress = InetAddress.getByName(dir);
                    
                    // Enviar metadatos primero (nombre y tamaño)
                    String metadata = selectedFile.getName() + "|" + selectedFile.length();
                    byte[] metadataBytes = metadata.getBytes();
                    DatagramPacket metadataPacket = new DatagramPacket(
                        metadataBytes, metadataBytes.length, serverAddress, pto);
                    socket.send(metadataPacket);
                    
                    // Esperar confirmación de metadatos recibidos
                    byte[] ackBuffer = new byte[256];
                    DatagramPacket ackPacket = new DatagramPacket(ackBuffer, ackBuffer.length);
                    socket.receive(ackPacket);
                    String ack = new String(ackPacket.getData(), 0, ackPacket.getLength());
                    if (!"ACK_METADATA".equals(ack.trim())) {
                        throw new IOException("Error en confirmación de metadatos");
                    }

                    // Manejar archivo vacío
                    if (selectedFile.length() == 0) {
                        // Enviar señal de END directamente para archivos vacíos
                        byte[] endSignal = "END".getBytes();
                        DatagramPacket endPacket = new DatagramPacket(
                            endSignal, endSignal.length, serverAddress, pto);
                        socket.send(endPacket);
                        
                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("Archivo vacío enviado con éxito!\n");
                        });
                        socket.close();
                        return;
                    }
                    
                    // Enviar archivo en paquetes
                    try(FileInputStream fis = new FileInputStream(selectedFile)){
                        byte[] buffer = new byte[1024]; // Tamaño más pequeño para UDP
                        int read;
                        long totalSent = 0;
                        int sequenceNumber = 0;

                        while ((read = fis.read(buffer)) != -1) {
                            // Crear paquete con número de secuencia y datos
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            DataOutputStream dos = new DataOutputStream(baos);
                            dos.writeInt(sequenceNumber++);
                            dos.write(buffer, 0, read);
                            byte[] packetData = baos.toByteArray();

                            DatagramPacket packet = new DatagramPacket(
                                packetData, packetData.length, serverAddress, pto);
                            socket.send(packet);

                            // Esperar ACK para este paquete
                            socket.receive(ackPacket);
                            ack = new String(ackPacket.getData(), 0, ackPacket.getLength());
                            if (!("ACK_" + (sequenceNumber-1)).equals(ack.trim())) {
                                // Reintentar en caso de fallo
                                sequenceNumber--;
                                continue;
                            }

                            totalSent += read;
                            final int progress = (int) ((totalSent * 100) / selectedFile.length());
                            SwingUtilities.invokeLater(() -> {
                                statusArea.append("\rProgreso: " + progress + "%");
                            });
                        }

                        // Enviar paquete de fin de transmisión
                        byte[] endSignal = "END".getBytes();
                        DatagramPacket endPacket = new DatagramPacket(
                            endSignal, endSignal.length, serverAddress, pto);
                        socket.send(endPacket);

                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("\nArchivo enviado con éxito!\n");
                        });

                        fis.close();
                        socket.close();
                    } catch (Exception ex){
                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("Error al enviar archivo: " + ex.getMessage() + "\n");
                        });
                        ex.printStackTrace();
                    }
                    
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
                    byte[] receiveBuffer = new byte[1024];
                    DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);

                    // 1. Recibir metadatos (nombre y tamaño)
                    receptionSocket.receive(packet);
                    String metadata = new String(packet.getData(), 0, packet.getLength());

                    // Validar metadatos
                    if (!metadata.contains("|")) {
                        statusArea.append("Error: Metadatos inválidos\n");
                        continue;
                    }

                    String[] parts = metadata.split("\\|");
                    String nombre = parts[0];
                    long tam = Long.parseLong(parts[1]);

                    // Limpieza del nombre de archivo
                    nombre = nombre.replace("..", "").replace("/", "").replace("\\", "");

                    // Enviar ACK de metadatos
                    byte[] ackData = "ACK_METADATA".getBytes();
                    DatagramPacket ackPacket = new DatagramPacket(
                        ackData, ackData.length, packet.getAddress(), packet.getPort());
                    receptionSocket.send(ackPacket);

                    // Preparar archivo de destino
                    File receivedFile = new File(currentRootPath, nombre);
                    
                    final String finalNombre = nombre;

                    // Manejo de archivo vacío
                    if (tam == 0) {
                        new File(currentRootPath + nombre).createNewFile();
                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("Archivo vacío recibido: " + finalNombre + "\n");
                            updateFileTree();
                        });
                        continue;
                    }
                    
                    // Crear flujo de salida para guardar el archivo
                    try(FileOutputStream fos = new FileOutputStream(receivedFile)){
                        // Recibir paquetes de datos
                        long recibidos = 0;
                        int expectedSeq = 0;
                        Map<Integer, byte[]> outOfOrderPackets = new HashMap<>();

                        while (recibidos < tam) {
                            receptionSocket.receive(packet);

                            // Verificar si es el paquete final
                            String dataStr = new String(packet.getData(), 0, packet.getLength());
                            if ("END".equals(dataStr.trim())) {
                                break;
                            }

                            // Procesar paquete normal
                            ByteArrayInputStream bais = new ByteArrayInputStream(
                                packet.getData(), 0, packet.getLength());
                            DataInputStream dis = new DataInputStream(bais);
                            int seqNumber = dis.readInt();
                            byte[] fileData = new byte[packet.getLength() - 4];
                            dis.readFully(fileData);

                            // Manejar paquetes en orden
                            if (seqNumber == expectedSeq) {
                                fos.write(fileData);
                                recibidos += fileData.length;
                                expectedSeq++;

                                // Procesar paquetes fuera de orden que estaban en buffer
                                while (outOfOrderPackets.containsKey(expectedSeq)) {
                                    fos.write(outOfOrderPackets.remove(expectedSeq));
                                    recibidos += outOfOrderPackets.get(expectedSeq).length;
                                    expectedSeq++;
                                }
                            } 
                            // Almacenar paquetes fuera de orden
                            else if (seqNumber > expectedSeq) {
                                outOfOrderPackets.put(seqNumber, fileData);
                            }

                            // Enviar ACK (incluso para paquetes fuera de orden)
                            ackData = ("ACK_" + seqNumber).getBytes();
                            ackPacket = new DatagramPacket(
                                ackData, ackData.length, packet.getAddress(), packet.getPort());
                            receptionSocket.send(ackPacket);

                            // Actualizar progreso
                            final int porcentaje = (int) ((recibidos * 100) / tam);
                            SwingUtilities.invokeLater(() -> {
                                statusArea.append("\rRecibiendo: " + porcentaje + "%");
                            });
                        }

                        fos.close();
                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("\nArchivo recibido: " + finalNombre + "\n");
                            updateFileTree();
                        });
                    } catch (Exception e){
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