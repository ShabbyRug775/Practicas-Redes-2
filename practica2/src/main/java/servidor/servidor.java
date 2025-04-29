package servidor;

import java.net.*;
import java.io.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.tree.*;
import java.util.*;
import java.util.concurrent.*;

public class servidor {
    // Constantes para el protocolo
    private static final int PACKET_SIZE = 1024;
    private static final int HEADER_SIZE = 10;
    private static final int DATA_SIZE = PACKET_SIZE - HEADER_SIZE;
    private static final int WINDOW_SIZE = 5;
    private static final int TIMEOUT = 1000;
    
    // Componentes de la interfaz gráfica
    private static JTextArea statusArea;
    private static String ruta_archivos;
    private static DefaultMutableTreeNode rootNode;
    private static JTree fileTree;
    private static DefaultTreeModel treeModel;
    
    // Buffer para paquetes recibidos
    private static ConcurrentHashMap<Integer, byte[]> receivedPackets = new ConcurrentHashMap<>();
    
    public static void main(String[] args) {
        // Configuración de la ventana principal
        JFrame frame = new JFrame("Servidor de Archivos UDP");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 500);
        frame.setLayout(new BorderLayout());

        // Panel superior con botones
        JPanel topPanel = new JPanel(new BorderLayout());
        JButton selectButton = new JButton("Seleccionar Carpeta de Destino");
        JButton refreshButton = new JButton("Actualizar");
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(selectButton);
        buttonPanel.add(refreshButton);
        topPanel.add(buttonPanel, BorderLayout.NORTH);

        // Panel central con división horizontal
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        // Configuración del árbol de archivos
        rootNode = new DefaultMutableTreeNode("Sistema de Archivos");
        treeModel = new DefaultTreeModel(rootNode);
        fileTree = new JTree(treeModel);
        fileTree.setEditable(false);
        JScrollPane treeScroll = new JScrollPane(fileTree);

        // Configuración del área de estado
        statusArea = new JTextArea();
        statusArea.setEditable(false);
        JScrollPane statusScroll = new JScrollPane(statusArea);

        splitPane.setLeftComponent(treeScroll);
        splitPane.setRightComponent(statusScroll);
        splitPane.setDividerLocation(250);

        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(splitPane, BorderLayout.CENTER);

        // Listener para selección de elementos en el árbol
        fileTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            if (node == null) return;
            
            Object nodeInfo = node.getUserObject();
            if (nodeInfo instanceof File) {
                File file = (File) nodeInfo;
                if (file.isDirectory()) {
                    statusArea.append("Directorio seleccionado: " + file.getAbsolutePath() + "\n");
                } else {
                    statusArea.append("Archivo seleccionado: " + file.getName() + 
                                     " (" + file.length() + " bytes)\n");
                }
            }
        });

        // Listener para el botón de selección de carpeta
        selectButton.addActionListener(e -> {
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

        frame.setVisible(true);
    }

    // Método para iniciar el servidor UDP
    private static void startServer() {
        new Thread(() -> {
            try {
                int pto = 8000;
                DatagramSocket serverSocket = new DatagramSocket(pto);
                statusArea.append("Servidor UDP iniciado en el puerto " + pto + "\n");
                
                // Asegurar que el directorio de destino exista
                File f2 = new File(ruta_archivos);
                if (!f2.exists()) {
                    f2.mkdirs();
                    statusArea.append("Carpeta creada: " + ruta_archivos + "\n");
                }
                f2.setWritable(true);
                
                byte[] receiveData = new byte[PACKET_SIZE];
                
                while (true) {
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    serverSocket.receive(receivePacket);
                    
                    // Procesar cada cliente en un hilo separado
                    new Thread(() -> handleClient(serverSocket, receivePacket)).start();
                }
            } catch (Exception e) {
                statusArea.append("Error en el servidor: " + e.getMessage() + "\n");
                e.printStackTrace();
            }
        }).start();
    }
    
    // Método para manejar la conexión con un cliente
    private static void handleClient(DatagramSocket socket, DatagramPacket initialPacket) {
        try {
            InetAddress clientAddress = initialPacket.getAddress();
            int clientPort = initialPacket.getPort();
            
            // Procesar paquete inicial (metadata)
            byte[] data = initialPacket.getData();
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bais);
            
            // Hacer las variables final o efectivamente final
            final String nombre = dis.readUTF().replace("..", "").replace("/", "").replace("\\", "");
            final long tam = dis.readLong();
            
            SwingUtilities.invokeLater(() -> {
                statusArea.append("Recibiendo archivo: " + nombre + " (" + tam + " bytes)\n");
            });
            
            // Preparar para recibir el archivo
            FileOutputStream fos = new FileOutputStream(ruta_archivos + nombre);
            receivedPackets.clear();
            
            int expectedSeqNum = 0;
            int lastAcked = -1;
            long totalReceived = 0;
            
            while (totalReceived < tam) {
                // Recibir paquete
                byte[] packetData = new byte[PACKET_SIZE];
                DatagramPacket packet = new DatagramPacket(packetData, packetData.length);
                socket.receive(packet);
                
                // Extraer número de secuencia y datos
                ByteArrayInputStream packetBais = new ByteArrayInputStream(packet.getData());
                DataInputStream packetDis = new DataInputStream(packetBais);
                int seqNum = packetDis.readInt();
                boolean isLast = packetDis.readBoolean();
                byte[] packetContent = new byte[packetDis.available()];
                packetDis.readFully(packetContent);
                
                // Verificar si es el paquete esperado
                if (seqNum == expectedSeqNum) {
                    // Escribir datos y enviar ACK
                    fos.write(packetContent);
                    totalReceived += packetContent.length;
                    sendAck(socket, clientAddress, clientPort, seqNum);
                    expectedSeqNum++;
                    lastAcked = seqNum;
                    
                    // Procesar paquetes en buffer que ahora están en orden
                    while (receivedPackets.containsKey(expectedSeqNum)) {
                        byte[] bufferedData = receivedPackets.remove(expectedSeqNum);
                        fos.write(bufferedData);
                        totalReceived += bufferedData.length;
                        sendAck(socket, clientAddress, clientPort, expectedSeqNum);
                        lastAcked = expectedSeqNum;
                        expectedSeqNum++;
                    }
                } 
                // Si está dentro de la ventana pero no es el esperado
                else if (seqNum > expectedSeqNum && seqNum <= expectedSeqNum + WINDOW_SIZE - 1) {
                    // Almacenar en buffer y enviar ACK
                    receivedPackets.put(seqNum, packetContent);
                    sendAck(socket, clientAddress, clientPort, seqNum);
                }
                // Si es un paquete ya confirmado, reenviar ACK
                else if (seqNum <= lastAcked) {
                    sendAck(socket, clientAddress, clientPort, seqNum);
                }
                
                // Actualizar progreso
                final int porcentaje = (int) ((totalReceived * 100) / tam);
                SwingUtilities.invokeLater(() -> {
                    statusArea.append("\rProgreso: " + porcentaje + "%");
                });
            }
            
            fos.close();
            SwingUtilities.invokeLater(() -> {
                statusArea.append("\nArchivo recibido: " + nombre + "\n");
                updateFileTree();
            });
            
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> {
                statusArea.append("Error al manejar cliente: " + e.getMessage() + "\n");
            });
            e.printStackTrace();
        }
    }
    
    // Método para enviar ACK
    private static void sendAck(DatagramSocket socket, InetAddress address, int port, int seqNum) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(seqNum);
        dos.close();
        
        byte[] ackData = baos.toByteArray();
        DatagramPacket ackPacket = new DatagramPacket(ackData, ackData.length, address, port);
        socket.send(ackPacket);
    }
    
    // Métodos auxiliares para el árbol de archivos
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