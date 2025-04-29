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

    // Componentes de la interfaz gráfica y variables de estado
    private static JTextArea statusArea;            // Área de texto para mostrar el estado del servidor
    private static String ruta_archivos;            // Ruta donde se almacenarán los archivos recibidos
    private static ServerSocket serverSocket;       // Socket del servidor para aceptar conexiones
    private static DefaultMutableTreeNode rootNode; // Nodo raíz para el árbol de archivos
    private static JTree fileTree;                  // Componente JTree para mostrar la estructura de archivos
    private static DefaultTreeModel treeModel;      // Modelo de datos para el JTree

    public static void main(String[] args) {
        // Configuración de la ventana principal
        JFrame frame = new JFrame("Servidor de Archivos");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 500);
        frame.setLayout(new BorderLayout());

        // Panel superior con botones de acción
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

        // Panel central con división horizontal (árbol de archivos y área de estado)
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
            if (node == null) {
                return;
            }

            // Muestra información del archivo/directorio seleccionado
            Object nodeInfo = node.getUserObject();
            if (nodeInfo instanceof File) {
                File file = (File) nodeInfo;
                if (file.isDirectory()) {
                    statusArea.append("Directorio seleccionado: " + file.getAbsolutePath() + "\n");
                } else {
                    statusArea.append("Archivo seleccionado: " + file.getName()
                            + " (" + file.length() + " bytes)\n");
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
                updateFileTree(); // Actualiza el árbol con la nueva ruta
                statusArea.append("Carpeta de destino seleccionada: " + ruta_archivos + "\n");
                startServer(); // Inicia el servidor después de seleccionar la carpeta
            }
        });

        // Listener para el botón de actualización
        refreshButton.addActionListener(e -> {
            if (ruta_archivos != null && !ruta_archivos.isEmpty()) {
                updateFileTree();
                statusArea.append("Contenido actualizado\n");
            }
        });

        // Listener para el botón de creación
        createButton.addActionListener(e -> {
            if (ruta_archivos == null) {
                JOptionPane.showMessageDialog(frame,
                        "Por favor seleccione una carpeta de destino primero",
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
                parentDir = new File(ruta_archivos);
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
            if (fileToDelete.getAbsolutePath().equals(ruta_archivos)) {
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

        frame.setVisible(true); // Muestra la ventana
    }

    // Método auxiliar para eliminar directorios recursivamente
    private static boolean deleteDirectory(File directory) {
        File[] allContents = directory.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file); // Elimina recursivamente el contenido
            }
        }
        return directory.delete(); // Elimina el directorio vacío
    }

    // Actualiza el árbol de archivos con el contenido del directorio actual
    private static void updateFileTree() {
        rootNode.removeAllChildren(); // Limpia el árbol
        if (ruta_archivos != null && !ruta_archivos.isEmpty()) {
            File rootFile = new File(ruta_archivos);
            DefaultMutableTreeNode root = new DefaultMutableTreeNode(rootFile);
            rootNode.add(root);
            populateTree(root, rootFile); // Puebla el árbol con el contenido
        }
        treeModel.reload(); // Recarga el modelo
        expandAllNodes(fileTree, 0, fileTree.getRowCount()); // Expande todos los nodos
    }

    // Expande todos los nodos del árbol recursivamente
    private static void expandAllNodes(JTree tree, int startingIndex, int rowCount) {
        for (int i = startingIndex; i < rowCount; ++i) {
            tree.expandRow(i);
        }
        if (tree.getRowCount() != rowCount) {
            expandAllNodes(tree, rowCount, tree.getRowCount()); // Expande nodos nuevos
        }
    }

    // Puebla el árbol con el contenido del directorio
    private static void populateTree(DefaultMutableTreeNode node, File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child);
                    node.add(childNode);
                    if (child.isDirectory()) {
                        populateTree(childNode, child); // Recursión para subdirectorios
                    }
                }
            }
        }
    }

    // Inicia el servidor en un hilo separado
    private static void startServer() {
        new Thread(() -> {
            try {
                int pto = 8000; // Puerto del servidor
                serverSocket = new ServerSocket(pto);
                serverSocket.setReuseAddress(true);

                // Asegura que el directorio de destino exista
                File f2 = new File(ruta_archivos);
                if (!f2.exists()) {
                    f2.mkdirs();
                    statusArea.append("Carpeta creada: " + ruta_archivos + "\n");
                }
                f2.setWritable(true);

                statusArea.append("Servidor iniciado en el puerto " + pto + "\n");

                // Bucle principal del servidor
                while (true) {
                    Socket cl = serverSocket.accept(); // Espera conexiones
                    statusArea.append("Cliente conectado desde " + cl.getInetAddress() + ":" + cl.getPort() + "\n");

                    // Maneja cada cliente en un hilo separado
                    final Socket clientSocket = cl;
                    new Thread(() -> handleClient(clientSocket)).start();
                }
            } catch (Exception e) {
                statusArea.append("Error en el servidor: " + e.getMessage() + "\n");
                e.printStackTrace();
            }
        }).start();
    }

    // Maneja la conexión con un cliente
    private static void handleClient(Socket cl) {
        try {
            DataInputStream dis = new DataInputStream(cl.getInputStream());
            // Recibe nombre y tamaño del archivo
            String nombre = dis.readUTF();
            long tam = dis.readLong();

            // Limpieza del nombre para seguridad
            nombre = nombre.replace("..", "").replace("/", "").replace("\\", "");

            // Muestra información en el área de estado
            final String finalNombre = nombre;
            SwingUtilities.invokeLater(() -> {
                statusArea.append("Recibiendo archivo: " + finalNombre + " (" + tam + " bytes)\n");
            });

            // Prepara para recibir el archivo
            DataOutputStream dos = new DataOutputStream(new FileOutputStream(ruta_archivos + nombre));
            long recibidos = 0;
            int l = 0;

            // Recibe el archivo en bloques
            while (recibidos < tam) {
                byte[] b = new byte[3500];
                l = dis.read(b);
                dos.write(b, 0, l);
                dos.flush();
                recibidos += l;
                final int porcentaje = (int) ((recibidos * 100) / tam);

                // Actualiza el progreso
                SwingUtilities.invokeLater(() -> {
                    statusArea.append("\rProgreso: " + porcentaje + "%");
                });
            }

            // Finalización de la recepción
            SwingUtilities.invokeLater(() -> {
                statusArea.append("\nArchivo recibido: " + finalNombre + "\n");
                updateFileTree(); // Actualiza el árbol con el nuevo archivo
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