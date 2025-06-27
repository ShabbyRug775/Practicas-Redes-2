package cliente;

import java.net.*;
import java.io.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.tree.*;
import javax.swing.event.*;

public class Cliente {

    // Componentes de la interfaz gráfica y variables de estado
    private static JTextArea statusArea;                    // Área de texto para mostrar el estado del servidor
    private static File selectedFile;                       // Archivo seleccionado para enviar
    private static DefaultMutableTreeNode rootNode;         // Nodo raíz para el árbol de archivos
    private static JTree fileTree;                          // Componente JTree para mostrar la estructura de archivos
    private static DefaultTreeModel treeModel;              // Nodo raíz para el árbol de archivos
    private static String currentRootPath;                  // Ruta donde se almacenarán los archivos recibidos
    private static ServerSocket receptionSocket;            // Socket del servidor para aceptar conexiones
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
                        updateFileTree();
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

        // Iniciar recepción de archivos
        startFileReception();
        frame.setVisible(true);
    }

    private static void startFileReception() {
        new Thread(() -> {
            try {
                receptionSocket = new ServerSocket(RECEPTION_PORT);
                statusArea.append("Cliente listo para recibir archivos en puerto " + RECEPTION_PORT + "\n");
                
                while (true) {
                    Socket connection = receptionSocket.accept();
                    statusArea.append("Conexión entrante para recibir archivo\n");
                    
                    DataInputStream dis = new DataInputStream(connection.getInputStream());
                    String nombre = dis.readUTF();
                    long tam = dis.readLong();
                    
                    nombre = nombre.replace("..", "").replace("/", "").replace("\\", "");
                    
                    File receivedFile = new File(currentRootPath, nombre);
                    DataOutputStream dos = new DataOutputStream(new FileOutputStream(receivedFile));
                    
                    final String nomfinal = nombre;
                    
                    long recibidos = 0;
                    int l;
                    while (recibidos < tam) {
                        byte[] b = new byte[3500];
                        l = dis.read(b);
                        dos.write(b, 0, l);
                        dos.flush();
                        recibidos += l;
                        final int porcentaje = (int) ((recibidos * 100) / tam);
                        
                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("\rRecibiendo: " + porcentaje + "%");
                        });
                    }
                    
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("\nArchivo recibido: " + nomfinal + "\n");
                        updateFileTree();
                    });
                    
                    dos.close();
                    dis.close();
                    connection.close();
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusArea.append("Error en recepción: " + e.getMessage() + "\n");
                });
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