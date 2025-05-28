package servidor;

import java.net.*;
import java.io.*;
import java.util.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.tree.*;
import javax.swing.filechooser.*;

public class servidor {

    private static JTextArea statusArea;
    private static ServerSocket serverSocket;
    private static DefaultListModel<String> roomListModel;
    private static DefaultListModel<String> userListModel;
    private static HashMap<String, ArrayList<ClientHandler>> rooms = new HashMap<>();
    private static HashMap<String, ClientHandler> users = new HashMap<>();
    private static String fileStoragePath;

    public static void main(String[] args) {
        JFrame frame = new JFrame("Servidor de Chat");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);
        frame.setLayout(new BorderLayout());

        // Panel superior
        JPanel topPanel = new JPanel(new BorderLayout());
        JButton selectFolderButton = new JButton("Seleccionar Carpeta para Archivos");
        JButton startButton = new JButton("Iniciar Servidor");
        topPanel.add(selectFolderButton, BorderLayout.WEST);
        topPanel.add(startButton, BorderLayout.EAST);

        // Panel central con pestañas
        JTabbedPane tabbedPane = new JTabbedPane();

        // Panel de estado
        statusArea = new JTextArea();
        statusArea.setEditable(false);
        JScrollPane statusScroll = new JScrollPane(statusArea);

        // Panel de salas
        JPanel roomPanel = new JPanel(new BorderLayout());
        roomListModel = new DefaultListModel<>();
        JList<String> roomList = new JList<>(roomListModel);
        JScrollPane roomScroll = new JScrollPane(roomList);
        roomPanel.add(new JLabel("Salas activas:"), BorderLayout.NORTH);
        roomPanel.add(roomScroll, BorderLayout.CENTER);

        // Panel de usuarios
        JPanel userPanel = new JPanel(new BorderLayout());
        userListModel = new DefaultListModel<>();
        JList<String> userList = new JList<>(userListModel);
        JScrollPane userScroll = new JScrollPane(userList);
        userPanel.add(new JLabel("Usuarios conectados:"), BorderLayout.NORTH);
        userPanel.add(userScroll, BorderLayout.CENTER);

        tabbedPane.addTab("Estado", statusScroll);
        tabbedPane.addTab("Salas", roomPanel);
        tabbedPane.addTab("Usuarios", userPanel);

        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(tabbedPane, BorderLayout.CENTER);

        // Listeners
        selectFolderButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                fileStoragePath = fileChooser.getSelectedFile().getAbsolutePath();
                if (!fileStoragePath.endsWith(File.separator)) {
                    fileStoragePath += File.separator;
                }
                statusArea.append("Carpeta para archivos seleccionada: " + fileStoragePath + "\n");
            }
        });

        startButton.addActionListener(e -> startServer());

        frame.setVisible(true);
    }

    private static void startServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(8000);
                serverSocket.setReuseAddress(true);
                statusArea.append("Servidor de chat iniciado en el puerto 8000\n");

                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    new ClientHandler(clientSocket).start();
                }
            } catch (IOException e) {
                statusArea.append("Error en el servidor: " + e.getMessage() + "\n");
            }
        }).start();
    }

    public static synchronized void addRoom(String roomName) {
        if (!rooms.containsKey(roomName)) {
            rooms.put(roomName, new ArrayList<>());
            roomListModel.addElement(roomName);
            statusArea.append("Nueva sala creada: " + roomName + "\n");
        }
    }

    public static synchronized void addUser(String username, ClientHandler handler) {
        users.put(username, handler);
        userListModel.addElement(username);
        statusArea.append("Usuario conectado: " + username + "\n");
    }

    public static synchronized void removeUser(String username) {
        users.remove(username);
        userListModel.removeElement(username);
        statusArea.append("Usuario desconectado: " + username + "\n");
    }

    public static synchronized void broadcastToRoom(String room, String message, String sender) {
        if (rooms.containsKey(room)) {
            for (ClientHandler client : rooms.get(room)) {
                if (!client.getUsername().equals(sender)) {
                    client.sendMessage("ROOM:" + room + ":" + sender + ":" + message);
                }
            }
        }
    }

    public static synchronized void sendPrivateMessage(String recipient, String message, String sender) {
        if (users.containsKey(recipient)) {
            users.get(recipient).sendMessage("PRIVATE:" + sender + ":" + message);
        }
    }

    public static synchronized void sendFileToRoom(String room, String filename, long size, String sender) {
        if (rooms.containsKey(room)) {
            for (ClientHandler client : rooms.get(room)) {
                if (!client.getUsername().equals(sender)) {
                    client.sendMessage("FILE_ROOM:" + room + ":" + sender + ":" + filename + ":" + size);
                }
            }
        }
    }

    public static synchronized void sendPrivateFile(String recipient, String filename, long size, String sender) {
        if (users.containsKey(recipient)) {
            users.get(recipient).sendMessage("FILE_PRIVATE:" + sender + ":" + filename + ":" + size);
        }
    }

    private static class ClientHandler extends Thread {
        private Socket socket;
        private DataInputStream dis;
        private DataOutputStream dos;
        private String username;
        private String currentRoom;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public String getUsername() {
            return username;
        }

        public void run() {
            try {
                dis = new DataInputStream(socket.getInputStream());
                dos = new DataOutputStream(socket.getOutputStream());

                // Autenticación
                username = dis.readUTF();
                addUser(username, this);

                // Manejo de mensajes
                while (true) {
                    String type = dis.readUTF();
                    
                    if (type.equals("JOIN_ROOM")) {
                        String room = dis.readUTF();
                        joinRoom(room);
                    } 
                    else if (type.equals("LEAVE_ROOM")) {
                        leaveRoom();
                    }
                    else if (type.equals("ROOM_MSG")) {
                        String room = dis.readUTF();
                        String message = dis.readUTF();
                        broadcastToRoom(room, message, username);
                    }
                    else if (type.equals("PRIVATE_MSG")) {
                        String recipient = dis.readUTF();
                        String message = dis.readUTF();
                        sendPrivateMessage(recipient, message, username);
                    }
                    else if (type.equals("FILE_ROOM")) {
                        String room = dis.readUTF();
                        String filename = dis.readUTF();
                        long size = dis.readLong();
                        sendFileToRoom(room, filename, size, username);
                        receiveFile(filename, size);
                    }
                    else if (type.equals("FILE_PRIVATE")) {
                        String recipient = dis.readUTF();
                        String filename = dis.readUTF();
                        long size = dis.readLong();
                        sendPrivateFile(recipient, filename, size, username);
                        receiveFile(filename, size);
                    }
                }
            } catch (IOException e) {
                try {
                    if (currentRoom != null) leaveRoom();
                    removeUser(username);
                    socket.close();
                } catch (IOException ex) {
                    statusArea.append("Error al cerrar conexión: " + ex.getMessage() + "\n");
                }
            }
        }

        private void joinRoom(String room) {
            if (currentRoom != null) leaveRoom();
            
            addRoom(room);
            rooms.get(room).add(this);
            currentRoom = room;
            sendMessage("JOINED_ROOM:" + room);
            statusArea.append(username + " se unió a la sala " + room + "\n");
        }

        private void leaveRoom() {
            if (currentRoom != null && rooms.containsKey(currentRoom)) {
                rooms.get(currentRoom).remove(this);
                sendMessage("LEFT_ROOM:" + currentRoom);
                statusArea.append(username + " abandonó la sala " + currentRoom + "\n");
                currentRoom = null;
            }
        }

        private void receiveFile(String filename, long size) throws IOException {
            filename = filename.replace("..", "").replace("/", "").replace("\\", "");
            File file = new File(fileStoragePath + filename);
            FileOutputStream fos = new FileOutputStream(file);
            
            long received = 0;
            byte[] buffer = new byte[4096];
            int read;
            
            while (received < size && (read = dis.read(buffer, 0, (int) Math.min(buffer.length, size - received))) != -1) {
                fos.write(buffer, 0, read);
                received += read;
            }
            
            fos.close();
            statusArea.append("Archivo recibido: " + filename + " (" + size + " bytes)\n");
        }

        public void sendMessage(String message) {
            try {
                dos.writeUTF(message);
                dos.flush();
            } catch (IOException e) {
                statusArea.append("Error al enviar mensaje a " + username + ": " + e.getMessage() + "\n");
            }
        }
    }
}