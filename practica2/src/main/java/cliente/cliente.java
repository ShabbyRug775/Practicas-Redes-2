package cliente;

import java.net.*;
import java.io.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.tree.*;
import java.util.*;
import javax.swing.filechooser.*;

public class cliente {

    private static JTextArea statusArea;
    private static File selectedFile;
    private static DefaultMutableTreeNode rootNode;
    private static JTree fileTree;
    private static DefaultTreeModel treeModel;
    private static String currentRootPath;
    private static final int PACKET_SIZE = 65000;
    private static final int WINDOW_SIZE = 5;

    public static void main(String[] args) {
        JFrame frame = new JFrame("Cliente de Archivos UDP (Go-Back-N)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(700, 600);
        frame.setLayout(new BorderLayout());

        JPanel serverPanel = new JPanel(new FlowLayout());
        JLabel serverLabel = new JLabel("Servidor:");
        JTextField serverField = new JTextField("127.0.0.1", 15);
        JLabel portLabel = new JLabel("Puerto:");
        JTextField portField = new JTextField("8000", 5);
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

        statusArea = new JTextArea();
        statusArea.setEditable(false);
        JScrollPane statusScroll = new JScrollPane(statusArea);

        mainSplitPane.setTopComponent(fileSplitPane);
        mainSplitPane.setBottomComponent(statusScroll);
        mainSplitPane.setDividerLocation(350);

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

        JButton loadFsButton = new JButton("Cargar Sistema de Archivos");
        serverPanel.add(loadFsButton);

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
                JOptionPane.showMessageDialog(frame,
                        "Por favor seleccione un directorio raíz primero",
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

        deleteButton.addActionListener(e -> {
            DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            if (selectedNode == null || selectedNode == rootNode) {
                JOptionPane.showMessageDialog(frame,
                        "Por favor seleccione un archivo o carpeta para eliminar",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            File fileToDelete = (File) selectedNode.getUserObject();
            if (fileToDelete.getAbsolutePath().equals(currentRootPath)) {
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
                        statusArea.append("Iniciando transferencia Go-Back-N a " + dir + ":" + pto + "...\n");
                    });

                    DatagramSocket clientSocket = new DatagramSocket();
                    InetAddress serverAddress = InetAddress.getByName(dir);
                    
                    String nombre = selectedFile.getName();
                    long tam = selectedFile.length();
                    
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(baos);
                    dos.writeUTF(nombre);
                    dos.writeLong(tam);
                    byte[] metadata = baos.toByteArray();
                    
                    DatagramPacket metadataPacket = new DatagramPacket(
                        metadata, metadata.length, serverAddress, pto);
                    clientSocket.send(metadataPacket);
                    
                    int base = 0;
                    int nextSeqNum = 0;
                    
                    final boolean[] transferComplete = new boolean[]{false};
                    final Map<Integer, byte[]> packetBuffer = new HashMap<>();
                    
                    final int bas = base;
                    final int nsn = nextSeqNum;
                    
                    java.util.Timer retransmitTimer = new java.util.Timer(true);
                    retransmitTimer.scheduleAtFixedRate(new TimerTask() {
                        @Override
                        public void run() {
                            synchronized (packetBuffer) {
                                if (!transferComplete[0] && !packetBuffer.isEmpty()) {
                                    SwingUtilities.invokeLater(() -> {
                                        statusArea.append("Timeout - Retransmitiendo ventana desde: " + bas + "\n");
                                    });
                                    
                                    try {
                                        for (int i = bas; i < bas + WINDOW_SIZE && i < nsn; i++) {
                                            byte[] data = packetBuffer.get(i);
                                            if (data != null) {
                                                DatagramPacket packet = new DatagramPacket(
                                                    data, data.length, serverAddress, pto);
                                                clientSocket.send(packet);
                                            }
                                        }
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                    }
                                }
                            }
                        }
                    }, 1000, 1000);
                    
                    FileInputStream fis = new FileInputStream(selectedFile);
                    long startTime = System.currentTimeMillis();
                    
                    while (!transferComplete[0]) {
                        while (nextSeqNum < base + WINDOW_SIZE && nextSeqNum * PACKET_SIZE < tam) {
                            long offset = nextSeqNum * (long)PACKET_SIZE;
                            int bytesToRead = (int)Math.min(PACKET_SIZE, tam - offset);
                            byte[] fileData = new byte[bytesToRead];
                            fis.read(fileData);
                            
                            ByteArrayOutputStream packetBaos = new ByteArrayOutputStream();
                            DataOutputStream packetDos = new DataOutputStream(packetBaos);
                            packetDos.writeInt(nextSeqNum);
                            packetDos.writeInt(bytesToRead);
                            packetDos.write(fileData);
                            
                            byte[] packetData = packetBaos.toByteArray();
                            
                            synchronized (packetBuffer) {
                                packetBuffer.put(nextSeqNum, packetData);
                            }
                            
                            DatagramPacket packet = new DatagramPacket(
                                packetData, packetData.length, serverAddress, pto);
                            clientSocket.send(packet);
                            
                            SwingUtilities.invokeLater(() -> {
                                statusArea.append("Enviado paquete: " + nsn + "\n");
                            });
                            
                            nextSeqNum++;
                        }
                        
                        clientSocket.setSoTimeout(500);
                        try {
                            byte[] ackData = new byte[4];
                            DatagramPacket ackPacket = new DatagramPacket(ackData, ackData.length);
                            clientSocket.receive(ackPacket);
                            
                            DataInputStream ackDis = new DataInputStream(
                                new ByteArrayInputStream(ackPacket.getData()));
                            int ackedSeq = ackDis.readInt();
                            
                            if (ackedSeq >= base) {
                                synchronized (packetBuffer) {
                                    for (int i = base; i <= ackedSeq; i++) {
                                        packetBuffer.remove(i);
                                    }
                                    base = ackedSeq + 1;
                                }
                                
                                SwingUtilities.invokeLater(() -> {
                                    statusArea.append("ACK recibido: " + ackedSeq + "\n");
                                });
                            }
                            
                            if (base * PACKET_SIZE >= tam) {
                                transferComplete[0] = true;
                                long endTime = System.currentTimeMillis();
                                double speed = (tam / 1024.0) / ((endTime - startTime) / 1000.0);
                                
                                SwingUtilities.invokeLater(() -> {
                                    statusArea.append("\nTransferencia completada!\n");
                                    statusArea.append(String.format("Velocidad promedio: %.2f KB/s\n", speed));
                                });
                            }
                        } catch (SocketTimeoutException ste) {
                            // Timeout manejado por el temporizador
                        }
                        
                        final int progress = (int)((base * PACKET_SIZE * 100) / tam);
                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("\rProgreso: " + progress + "%");
                        });
                    }
                    
                    retransmitTimer.cancel();
                    fis.close();
                    clientSocket.close();
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Error en la transferencia: " + ex.getMessage() + "\n");
                    });
                    ex.printStackTrace();
                }
            }).start();
        });

        frame.setVisible(true);
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