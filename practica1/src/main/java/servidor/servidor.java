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
    private static ServerSocket serverSocket;
    private static DefaultMutableTreeNode rootNode;
    private static JTree fileTree;
    private static DefaultTreeModel treeModel;

    public static void main(String[] args) {
        JFrame frame = new JFrame("Servidor de Archivos");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 500);
        frame.setLayout(new BorderLayout());

        // Panel superior para selección de carpeta
        JPanel topPanel = new JPanel(new BorderLayout());
        JButton selectButton = new JButton("Seleccionar Carpeta de Destino");
        JButton refreshButton = new JButton("Actualizar");
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(selectButton);
        buttonPanel.add(refreshButton);
        topPanel.add(buttonPanel, BorderLayout.NORTH);

        // Panel central con árbol de directorios y área de estado
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        
        // Componente JTree para navegar por el sistema de archivos
        rootNode = new DefaultMutableTreeNode("Sistema de Archivos");
        treeModel = new DefaultTreeModel(rootNode);
        fileTree = new JTree(treeModel);
        fileTree.setEditable(false);
        JScrollPane treeScroll = new JScrollPane(fileTree);
        
        // Área de estado
        statusArea = new JTextArea();
        statusArea.setEditable(false);
        JScrollPane statusScroll = new JScrollPane(statusArea);
        
        splitPane.setLeftComponent(treeScroll);
        splitPane.setRightComponent(statusScroll);
        splitPane.setDividerLocation(250);
        
        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(splitPane, BorderLayout.CENTER);
        
        // Configurar el árbol de archivos
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

        refreshButton.addActionListener(e -> {
            if (ruta_archivos != null && !ruta_archivos.isEmpty()) {
                updateFileTree();
                statusArea.append("Contenido actualizado\n");
            }
        });

        frame.setVisible(true);
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

    private static void startServer() {
        new Thread(() -> {
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

                statusArea.append("Servidor iniciado en el puerto " + pto + "\n");

                while (true) {
                    Socket cl = serverSocket.accept();
                    statusArea.append("Cliente conectado desde " + cl.getInetAddress() + ":" + cl.getPort() + "\n");
                    
                    final Socket clientSocket = cl;
                    new Thread(() -> handleClient(clientSocket)).start();
                }
            } catch (Exception e) {
                statusArea.append("Error en el servidor: " + e.getMessage() + "\n");
                e.printStackTrace();
            }
        }).start();
    }

    private static void handleClient(Socket cl) {
        try {
            DataInputStream dis = new DataInputStream(cl.getInputStream());
            String nombre = dis.readUTF();
            long tam = dis.readLong();
            
            nombre = nombre.replace("..", "").replace("/", "").replace("\\", "");
            
            final String finalNombre = nombre;
            SwingUtilities.invokeLater(() -> {
                statusArea.append("Recibiendo archivo: " + finalNombre + " (" + tam + " bytes)\n");
            });
            
            DataOutputStream dos = new DataOutputStream(new FileOutputStream(ruta_archivos + nombre));
            long recibidos = 0;
            int l = 0;
            
            while (recibidos < tam) {
                byte[] b = new byte[3500];
                l = dis.read(b);
                dos.write(b, 0, l);
                dos.flush();
                recibidos += l;
                final int porcentaje = (int) ((recibidos * 100) / tam);
                
                SwingUtilities.invokeLater(() -> {
                    statusArea.append("\rProgreso: " + porcentaje + "%");
                });
            }
            
            SwingUtilities.invokeLater(() -> {
                statusArea.append("\nArchivo recibido: " + finalNombre + "\n");
                updateFileTree(); // Actualizar árbol después de recibir archivo
            });
            
            dos.close();
            dis.close();
            cl.close();
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> {
                statusArea.append("Error al manejar cliente: " + e.getMessage() + "\n");
            });
            e.printStackTrace();
        }
    }
}