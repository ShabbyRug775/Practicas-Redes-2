package cliente;

import java.net.*;
import java.io.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.tree.*;
import java.util.*;
import java.util.concurrent.*;

public class cliente {
    // Constantes para el protocolo
    private static final int PACKET_SIZE = 1024;
    private static final int HEADER_SIZE = 10;
    private static final int DATA_SIZE = PACKET_SIZE - HEADER_SIZE;
    private static final int WINDOW_SIZE = 5;
    private static final int TIMEOUT = 1000;
    private static final int MAX_RETRIES = 5;
    
    // Componentes de la interfaz gráfica
    private static JTextArea statusArea;
    private static File selectedFile;
    private static DefaultMutableTreeNode rootNode;
    private static JTree fileTree;
    private static DefaultTreeModel treeModel;
    private static String currentRootPath;
    
    public static void main(String[] args) {
        // Configuración de la ventana principal
        JFrame frame = new JFrame("Cliente de Archivos UDP");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(700, 600);
        frame.setLayout(new BorderLayout());

        // Panel superior para configuración del servidor
        JPanel serverPanel = new JPanel(new FlowLayout());
        JLabel serverLabel = new JLabel("Servidor:");
        JTextField serverField = new JTextField("127.0.0.1", 15);
        JLabel portLabel = new JLabel("Puerto:");
        JTextField portField = new JTextField("8000", 5);
        serverPanel.add(serverLabel);
        serverPanel.add(serverField);
        serverPanel.add(portLabel);
        serverPanel.add(portField);

        // Panel central con división vertical
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);

        // Panel para el árbol de archivos y botones
        JSplitPane fileSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        // Configuración del árbol de archivos
        rootNode = new DefaultMutableTreeNode("Sistema de Archivos");
        treeModel = new DefaultTreeModel(rootNode);
        fileTree = new JTree(treeModel);
        fileTree.setEditable(false);
        JScrollPane treeScroll = new JScrollPane(fileTree);

        // Panel de información y botones
        JPanel infoPanel = new JPanel(new BorderLayout());
        JButton sendButton = new JButton("Enviar Archivo Seleccionado");
        JButton refreshButton = new JButton("Actualizar");
        JButton loadFsButton = new JButton("Cargar Sistema de Archivos");
        JLabel fileInfoLabel = new JLabel("Archivo seleccionado: Ninguno");
        
        JPanel buttonPanel = new JPanel(new GridLayout(4, 1));
        buttonPanel.add(sendButton);
        buttonPanel.add(refreshButton);
        buttonPanel.add(loadFsButton);
        
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

        frame.add(serverPanel, BorderLayout.NORTH);
        frame.add(mainSplitPane, BorderLayout.CENTER);

        // Listener para selección de elementos en el árbol
        fileTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            if (node == null) return;
            
            Object nodeInfo = node.getUserObject();
            if (nodeInfo instanceof File) {
                File file = (File) nodeInfo;
                if (!file.isDirectory()) {
                    selectedFile = file;
                    fileInfoLabel.setText("Archivo seleccionado: " + file.getName() + 
                                       " (" + file.length() + " bytes)");
                } else {
                    selectedFile = null;
                    fileInfoLabel.setText("Directorio seleccionado: " + file.getName());
                }
            }
        });

        // Listener para el botón de cargar sistema de archivos
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

        // Listener para el botón de actualización
        refreshButton.addActionListener(e -> {
            if (currentRootPath != null && !currentRootPath.isEmpty()) {
                updateFileTree();
                statusArea.append("Contenido actualizado\n");
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
            
            new Thread(() -> {
                try {
                    String dir = serverField.getText();
                    int pto = Integer.parseInt(portField.getText());
                    
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Conectando al servidor " + dir + ":" + pto + "...\n");
                    });
                    
                    // Usar socket UDP
                    DatagramSocket clientSocket = new DatagramSocket();
                    InetAddress serverAddress = InetAddress.getByName(dir);
                    
                    // Enviar metadatos primero
                    String nombre = selectedFile.getName();
                    long tam = selectedFile.length();
                    
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(baos);
                    dos.writeUTF(nombre);
                    dos.writeLong(tam);
                    dos.close();
                    
                    byte[] metaData = baos.toByteArray();
                    DatagramPacket metaPacket = new DatagramPacket(metaData, metaData.length, serverAddress, pto);
                    clientSocket.send(metaPacket);
                    
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Enviando archivo " + nombre + " (" + tam + " bytes)...\n");
                    });
                    
                    // Preparar para enviar el archivo
                    FileInputStream fis = new FileInputStream(selectedFile);
                    byte[] fileBuffer = new byte[DATA_SIZE];
                    int bytesRead;
                    int seqNum = 0;
                    int base = 0;
                    int nextSeqNum = 0;
                    Map<Integer, byte[]> windowPackets = new HashMap<>();
                    Map<Integer, Long> sendTimes = new HashMap<>();
                    Map<Integer, Integer> retryCounts = new HashMap<>();
                    
                    while (base * DATA_SIZE < tam || !windowPackets.isEmpty()) {
                        // Llenar la ventana
                        while (nextSeqNum < base + WINDOW_SIZE && nextSeqNum * DATA_SIZE < tam) {
                            bytesRead = fis.read(fileBuffer);
                            byte[] packetData = new byte[HEADER_SIZE + bytesRead];
                            
                            ByteArrayOutputStream packetBaos = new ByteArrayOutputStream();
                            DataOutputStream packetDos = new DataOutputStream(packetBaos);
                            packetDos.writeInt(nextSeqNum);
                            packetDos.writeBoolean((nextSeqNum + 1) * DATA_SIZE >= tam);
                            packetDos.write(fileBuffer, 0, bytesRead);
                            packetDos.close();
                            
                            byte[] packetToSend = packetBaos.toByteArray();
                            windowPackets.put(nextSeqNum, packetToSend);
                            sendPacket(clientSocket, serverAddress, pto, nextSeqNum, packetToSend);
                            sendTimes.put(nextSeqNum, System.currentTimeMillis());
                            retryCounts.put(nextSeqNum, 1);
                            nextSeqNum++;
                        }
                        
                        // Esperar ACKs con timeout
                        clientSocket.setSoTimeout(TIMEOUT);
                        try {
                            byte[] ackData = new byte[4];
                            DatagramPacket ackPacket = new DatagramPacket(ackData, ackData.length);
                            clientSocket.receive(ackPacket);
                            
                            ByteArrayInputStream ackBais = new ByteArrayInputStream(ackPacket.getData());
                            DataInputStream ackDis = new DataInputStream(ackBais);
                            int ackedSeqNum = ackDis.readInt();
                            
                            // Mover la ventana
                            if (ackedSeqNum >= base) {
                                for (int i = base; i <= ackedSeqNum; i++) {
                                    windowPackets.remove(i);
                                    sendTimes.remove(i);
                                    retryCounts.remove(i);
                                }
                                base = ackedSeqNum + 1;
                            }
                            
                            // Actualizar progreso
                            final int porcentaje = (int) ((base * DATA_SIZE * 100) / tam);
                            SwingUtilities.invokeLater(() -> {
                                statusArea.append("\rProgreso: " + porcentaje + "%");
                            });
                            
                        } catch (SocketTimeoutException ste) {
                            // Reenviar paquetes no confirmados
                            for (int i = base; i < nextSeqNum; i++) {
                                if (windowPackets.containsKey(i) && 
                                    System.currentTimeMillis() - sendTimes.get(i) > TIMEOUT) {
                                    
                                    if (retryCounts.get(i) >= MAX_RETRIES) {
                                        // Crear una copia final de i para usar en el lambda
                                        final int packetNumber = i;
                                        SwingUtilities.invokeLater(() -> {
                                            statusArea.append("\nError: Máximo de reintentos alcanzado para paquete " + packetNumber + "\n");
                                        });
                                        fis.close();
                                        clientSocket.close();
                                        return;
                                    }
                                    
                                    byte[] packetToResend = windowPackets.get(i);
                                    sendPacket(clientSocket, serverAddress, pto, i, packetToResend);
                                    sendTimes.put(i, System.currentTimeMillis());
                                    retryCounts.put(i, retryCounts.get(i) + 1);
                                }
                            }
                        }
                    }
                    
                    fis.close();
                    clientSocket.close();
                    
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("\nArchivo enviado con éxito!\n");
                        updateFileTree();
                    });
                    
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Error al enviar archivo: " + ex.getMessage() + "\n");
                    });
                    ex.printStackTrace();
                }
            }).start();
        });
        
        frame.setVisible(true);
    }
    
    // Método auxiliar para enviar paquetes
    private static void sendPacket(DatagramSocket socket, InetAddress address, int port, int seqNum, byte[] data) throws IOException {
        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
        socket.send(packet);
    }
    
    // Métodos auxiliares para el árbol de archivos
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
    
    private static void expandAllNodes(JTree tree, int startingIndex, int rowCount) {
        for (int i = startingIndex; i < rowCount; ++i) {
            tree.expandRow(i);
        }
        if (tree.getRowCount() != rowCount) {
            expandAllNodes(tree, rowCount, tree.getRowCount());
        }
    }
}