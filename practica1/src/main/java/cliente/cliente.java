package cliente;

import java.net.*;
import java.io.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.tree.*;
import javax.swing.event.*;

public class cliente {
    private static JTextArea statusArea;
    private static File selectedFile;
    private static DefaultMutableTreeNode rootNode;
    private static JTree fileTree;
    private static DefaultTreeModel treeModel;
    private static String currentRootPath;

    public static void main(String[] args) {
        JFrame frame = new JFrame("Cliente de Archivos");
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

        // Panel central con navegador de archivos y área de estado
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        
        // Panel para el árbol de archivos y botones
        JSplitPane fileSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        
        // Árbol de archivos
        rootNode = new DefaultMutableTreeNode("Sistema de Archivos");
        treeModel = new DefaultTreeModel(rootNode);
        fileTree = new JTree(treeModel);
        fileTree.setEditable(false);
        JScrollPane treeScroll = new JScrollPane(fileTree);
        
        // Panel de botones e información
        JPanel infoPanel = new JPanel(new BorderLayout());
        JButton sendButton = new JButton("Enviar Archivo Seleccionado");
        JButton refreshButton = new JButton("Actualizar");
        JPanel buttonPanel = new JPanel(new GridLayout(2, 1));
        buttonPanel.add(sendButton);
        buttonPanel.add(refreshButton);
        
        JLabel fileInfoLabel = new JLabel("Archivo seleccionado: Ninguno");
        infoPanel.add(fileInfoLabel, BorderLayout.NORTH);
        infoPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        fileSplitPane.setLeftComponent(treeScroll);
        fileSplitPane.setRightComponent(infoPanel);
        fileSplitPane.setDividerLocation(400);
        
        // Área de estado
        statusArea = new JTextArea();
        statusArea.setEditable(false);
        JScrollPane statusScroll = new JScrollPane(statusArea);
        
        mainSplitPane.setTopComponent(fileSplitPane);
        mainSplitPane.setBottomComponent(statusScroll);
        mainSplitPane.setDividerLocation(350);
        
        frame.add(serverPanel, BorderLayout.NORTH);
        frame.add(mainSplitPane, BorderLayout.CENTER);

        // Configurar el árbol de archivos
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

        // Botón para cargar el sistema de archivos
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
                    
                    while (enviados < tam) {
                        byte[] b = new byte[3500];
                        l = dis.read(b);
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
                        updateFileTree(); // Actualizar árbol después de enviar
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
            }).start();
        });

        frame.setVisible(true);
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