package servidor;

import java.net.*;
import java.io.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.tree.*;
import javax.swing.event.*;
import javax.swing.filechooser.*;

public class servidor {

    private static JTextArea statusArea;
    private static String ruta_archivos;
    private static DatagramSocket serverSocket;
    private static DefaultMutableTreeNode rootNode;
    private static JTree fileTree;
    private static DefaultTreeModel treeModel;
    private static final int PACKET_SIZE = 65000;

    public static void main(String[] args) {
        JFrame frame = new JFrame("Servidor de Archivos UDP (Go-Back-N)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 500);
        frame.setLayout(new BorderLayout());

        JPanel topPanel = new JPanel(new BorderLayout());
        JButton selectButton = new JButton("Seleccionar Carpeta de Destino");
        JButton refreshButton = new JButton("Actualizar");
        JButton createButton = new JButton("Crear Archivo/Carpeta");
        JButton deleteButton = new JButton("Eliminar Seleccionado");
        JButton renameButton = new JButton("Renombrar Seleccionado");
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(selectButton);
        buttonPanel.add(createButton);
        buttonPanel.add(deleteButton);
        buttonPanel.add(renameButton);
        buttonPanel.add(refreshButton);
        topPanel.add(buttonPanel, BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        rootNode = new DefaultMutableTreeNode("Sistema de Archivos");
        treeModel = new DefaultTreeModel(rootNode);
        fileTree = new JTree(treeModel);
        fileTree.setEditable(false);
        JScrollPane treeScroll = new JScrollPane(fileTree);

        statusArea = new JTextArea();
        statusArea.setEditable(false);
        JScrollPane statusScroll = new JScrollPane(statusArea);

        splitPane.setLeftComponent(treeScroll);
        splitPane.setRightComponent(statusScroll);
        splitPane.setDividerLocation(250);

        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(splitPane, BorderLayout.CENTER);

        fileTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            if (node == null) return;

            Object nodeInfo = node.getUserObject();
            if (nodeInfo instanceof File) {
                File file = (File) nodeInfo;
                if (file.isDirectory()) {
                    statusArea.append("Directorio seleccionado: " + file.getAbsolutePath() + "\n");
                } else {
                    statusArea.append("Archivo seleccionado: " + file.getName() + " (" + file.length() + " bytes)\n");
                }
            }
        });

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
                startGBNServer();
            }
        });

        refreshButton.addActionListener(e -> {
            if (ruta_archivos != null && !ruta_archivos.isEmpty()) {
                updateFileTree();
                statusArea.append("Contenido actualizado\n");
            }
        });

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

            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return;

            String name = JOptionPane.showInputDialog(frame, "Ingrese el nombre:");
            if (name == null || name.trim().isEmpty()) return;

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

        frame.setVisible(true);
    }

    private static void startGBNServer() {
        new Thread(() -> {
            try {
                int pto = 8000;
                serverSocket = new DatagramSocket(pto);
                byte[] buffer = new byte[PACKET_SIZE + 12];

                File f2 = new File(ruta_archivos);
                if (!f2.exists()) {
                    f2.mkdirs();
                    statusArea.append("Carpeta creada: " + ruta_archivos + "\n");
                }

                statusArea.append("Servidor Go-Back-N iniciado en el puerto " + pto + "\n");

                while (true) {
                    DatagramPacket metadataPacket = new DatagramPacket(buffer, buffer.length);
                    serverSocket.receive(metadataPacket);
                    
                    ByteArrayInputStream bais = new ByteArrayInputStream(metadataPacket.getData());
                    DataInputStream dis = new DataInputStream(bais);
                    String nombre = dis.readUTF();
                    long tam = dis.readLong();
                    
                    // Muestra información en el área de estado
                    final String finalNombre = nombre;
                    
                    nombre = nombre.replace("..", "").replace("/", "").replace("\\", "");
                    
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Recibiendo archivo: " + finalNombre + " (" + tam + " bytes)\n");
                    });

                    FileOutputStream fos = new FileOutputStream(ruta_archivos + nombre);
                    InetAddress clientAddress = metadataPacket.getAddress();
                    int clientPort = metadataPacket.getPort();
                    
                    int expectedSeq = 0;
                    
                    while (true) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        serverSocket.receive(packet);
                        
                        ByteArrayInputStream packetBais = new ByteArrayInputStream(packet.getData());
                        DataInputStream packetDis = new DataInputStream(packetBais);
                        int seqNum = packetDis.readInt();
                        int length = packetDis.readInt();
                        byte[] fileData = new byte[length];
                        packetDis.readFully(fileData);
                        
                        if (seqNum == expectedSeq) {
                            fos.write(fileData);
                            expectedSeq++;
                            
                            ByteArrayOutputStream ackBaos = new ByteArrayOutputStream();
                            DataOutputStream ackDos = new DataOutputStream(ackBaos);
                            ackDos.writeInt(expectedSeq - 1);
                            
                            byte[] ackData = ackBaos.toByteArray();
                            DatagramPacket ackPacket = new DatagramPacket(
                                ackData, ackData.length, clientAddress, clientPort);
                            serverSocket.send(ackPacket);
                            
                            // Muestra información en el área de estado
                            final int finales = expectedSeq;
                            
                            SwingUtilities.invokeLater(() -> {
                                statusArea.append("ACK enviado: " + (finales - 1) + "\n");
                            });
                        } else {
                            ByteArrayOutputStream ackBaos = new ByteArrayOutputStream();
                            DataOutputStream ackDos = new DataOutputStream(ackBaos);
                            ackDos.writeInt(expectedSeq - 1);
                            
                            byte[] ackData = ackBaos.toByteArray();
                            DatagramPacket ackPacket = new DatagramPacket(
                                ackData, ackData.length, clientAddress, clientPort);
                            serverSocket.send(ackPacket);
                        }
                        
                        if (expectedSeq * PACKET_SIZE >= tam) {
                            break;
                        }
                    }
                    
                    fos.close();
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("\nArchivo recibido completamente: " + finalNombre + "\n");
                        updateFileTree();
                    });
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusArea.append("Error en el servidor: " + e.getMessage() + "\n");
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