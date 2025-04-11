package cliente;

import java.net.*;
import java.io.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.tree.*;
import javax.swing.event.*;

public class cliente {

    // Componentes de la interfaz gráfica y variables de estado
    private static JTextArea statusArea;            // Área de texto para mostrar el estado
    private static File selectedFile;               // Archivo seleccionado para enviar
    private static DefaultMutableTreeNode rootNode; // Nodo raíz para el árbol de archivos
    private static JTree fileTree;                  // Componente JTree para mostrar la estructura de archivos
    private static DefaultTreeModel treeModel;      // Modelo de datos para el JTree
    private static String currentRootPath;          // Ruta actual del directorio raíz

    public static void main(String[] args) {
        // Configuración de la ventana principal
        JFrame frame = new JFrame("Cliente de Archivos");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(700, 600);
        frame.setLayout(new BorderLayout());

        // Panel superior para configuración del servidor
        JPanel serverPanel = new JPanel(new FlowLayout());
        JLabel serverLabel = new JLabel("Servidor:");
        JTextField serverField = new JTextField("127.0.0.1", 15); // Campo para la IP del servidor
        JLabel portLabel = new JLabel("Puerto:");
        JTextField portField = new JTextField("8000", 5); // Campo para el puerto
        serverPanel.add(serverLabel);
        serverPanel.add(serverField);
        serverPanel.add(portLabel);
        serverPanel.add(portField);

        // Panel central con división vertical (navegador de archivos y área de estado)
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);

        // Panel para el árbol de archivos y botones (división horizontal)
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

        frame.add(serverPanel, BorderLayout.NORTH);
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

        // Botón para cargar el sistema de archivos
        JButton loadFsButton = new JButton("Cargar Sistema de Archivos");
        serverPanel.add(loadFsButton);

        // Listener para el botón de cargar sistema de archivos
        loadFsButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int returnVal = fileChooser.showOpenDialog(frame);
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                currentRootPath = fileChooser.getSelectedFile().getAbsolutePath();
                statusArea.append("Directorio raíz seleccionado: " + currentRootPath + "\n");
                updateFileTree(); // Actualiza el árbol con la nueva ruta
            }
        });

        // Listener para el botón de actualización
        refreshButton.addActionListener(e -> {
            if (currentRootPath != null && !currentRootPath.isEmpty()) {
                updateFileTree();
                statusArea.append("Contenido actualizado\n");
            }
        });

        // Listener para el botón de creación
        createButton.addActionListener(e -> {
            if (currentRootPath == null) {
                JOptionPane.showMessageDialog(frame,
                        "Por favor seleccione un directorio raíz primero",
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
                parentDir = new File(currentRootPath);
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
            if (fileToDelete.getAbsolutePath().equals(currentRootPath)) {
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
                JOptionPane.showMessageDialog(frame,
                        "Por favor seleccione un archivo primero",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Envía el archivo en un hilo separado para no bloquear la interfaz
            new Thread(() -> {
                try {
                    String dir = serverField.getText(); // Obtiene dirección del servidor
                    int pto = Integer.parseInt(portField.getText()); // Obtiene puerto

                    // Muestra estado de conexión
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Conectando al servidor " + dir + ":" + pto + "...\n");
                    });

                    Socket cl = new Socket(dir, pto); // Establece conexión

                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("Conexión establecida. Enviando archivo...\n");
                    });

                    // Prepara información del archivo
                    String nombre = selectedFile.getName();
                    String path = selectedFile.getAbsolutePath();
                    long tam = selectedFile.length();

                    // Flujos para enviar datos
                    DataOutputStream dos = new DataOutputStream(cl.getOutputStream());
                    DataInputStream dis = new DataInputStream(new FileInputStream(path));

                    // Envía metadatos (nombre y tamaño)
                    dos.writeUTF(nombre);
                    dos.flush();
                    dos.writeLong(tam);
                    dos.flush();

                    long enviados = 0;
                    int l = 0;

                    // Envía el archivo en bloques
                    while (enviados < tam) {
                        byte[] b = new byte[3500];
                        l = dis.read(b);
                        dos.write(b, 0, l);
                        dos.flush();
                        enviados += l;
                        final int porcentaje = (int) ((enviados * 100) / tam);

                        // Actualiza progreso
                        SwingUtilities.invokeLater(() -> {
                            statusArea.append("\rProgreso: " + porcentaje + "%");
                        });
                    }

                    // Finalización del envío
                    SwingUtilities.invokeLater(() -> {
                        statusArea.append("\nArchivo enviado con éxito!\n");
                        updateFileTree(); // Actualiza el árbol (por si hubo cambios)
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

        frame.setVisible(true); // Muestra la ventana
    }

    // Método auxiliar para eliminar directorios recursivamente (igual que en servidor)
    private static boolean deleteDirectory(File directory) {
        File[] allContents = directory.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return directory.delete();
    }

    // Actualiza el árbol de archivos (similar al método en servidor)
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

    // Expande todos los nodos del árbol (igual que en servidor)
    private static void expandAllNodes(JTree tree, int startingIndex, int rowCount) {
        for (int i = startingIndex; i < rowCount; ++i) {
            tree.expandRow(i);
        }
        if (tree.getRowCount() != rowCount) {
            expandAllNodes(tree, rowCount, tree.getRowCount());
        }
    }

    // Puebla el árbol con el contenido del directorio (igual que en servidor)
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