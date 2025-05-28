package cliente;

import java.net.*;
import java.io.*;
import java.util.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.tree.*;
import javax.swing.filechooser.*;

public class cliente {

    private static JTextArea chatArea;
    private static JList<String> userList;
    private static DefaultListModel<String> userListModel;
    private static JList<String> roomList;
    private static DefaultListModel<String> roomListModel;
    private static JTextField messageField;
    private static JTextField usernameField;
    private static JComboBox<String> recipientCombo;
    private static Socket socket;
    private static DataInputStream dis;
    private static DataOutputStream dos;
    private static String username;
    private static String currentRoom;
    private static String fileStoragePath;

    public static void main(String[] args) {
        JFrame frame = new JFrame("Cliente de Chat");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(900, 700);
        frame.setLayout(new BorderLayout());

        // Panel de conexión
        JPanel connectionPanel = new JPanel(new FlowLayout());
        JLabel serverLabel = new JLabel("Servidor:");
        JTextField serverField = new JTextField("localhost", 10);
        JLabel portLabel = new JLabel("Puerto:");
        JTextField portField = new JTextField("8000", 5);
        JLabel userLabel = new JLabel("Usuario:");
        usernameField = new JTextField(10);
        JButton connectButton = new JButton("Conectar");
        JButton disconnectButton = new JButton("Desconectar");
        disconnectButton.setEnabled(false);

        connectionPanel.add(serverLabel);
        connectionPanel.add(serverField);
        connectionPanel.add(portLabel);
        connectionPanel.add(portField);
        connectionPanel.add(userLabel);
        connectionPanel.add(usernameField);
        connectionPanel.add(connectButton);
        connectionPanel.add(disconnectButton);

        // Panel principal con división vertical
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);

        // Panel de chat superior
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        JScrollPane chatScroll = new JScrollPane(chatArea);
        messageField = new JTextField();
        JButton sendButton = new JButton("Enviar");
        JButton sendFileButton = new JButton("Enviar Archivo");

        JPanel messagePanel = new JPanel(new BorderLayout());
        messagePanel.add(messageField, BorderLayout.CENTER);
        JPanel buttonPanel = new JPanel(new GridLayout(1, 2));
        buttonPanel.add(sendButton);
        buttonPanel.add(sendFileButton);
        messagePanel.add(buttonPanel, BorderLayout.EAST);

        chatPanel.add(chatScroll, BorderLayout.CENTER);
        chatPanel.add(messagePanel, BorderLayout.SOUTH);

        // Panel inferior con división horizontal
        JSplitPane bottomSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        // Panel de salas
        JPanel roomPanel = new JPanel(new BorderLayout());
        roomListModel = new DefaultListModel<>();
        roomList = new JList<>(roomListModel);
        JScrollPane roomScroll = new JScrollPane(roomList);
        JTextField newRoomField = new JTextField();
        JButton createRoomButton = new JButton("Crear/Unirse");
        JButton leaveRoomButton = new JButton("Salir");

        JPanel roomControlPanel = new JPanel(new BorderLayout());
        roomControlPanel.add(newRoomField, BorderLayout.CENTER);
        roomControlPanel.add(createRoomButton, BorderLayout.EAST);

        JPanel roomButtonPanel = new JPanel(new GridLayout(1, 2));
        roomButtonPanel.add(createRoomButton);
        roomButtonPanel.add(leaveRoomButton);

        roomPanel.add(new JLabel("Salas disponibles:"), BorderLayout.NORTH);
        roomPanel.add(roomScroll, BorderLayout.CENTER);
        roomPanel.add(roomControlPanel, BorderLayout.SOUTH);

        // Panel de usuarios
        JPanel userPanel = new JPanel(new BorderLayout());
        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        JScrollPane userScroll = new JScrollPane(userList);
        JLabel recipientLabel = new JLabel("Enviar privado a:");
        recipientCombo = new JComboBox<>();
        recipientCombo.addItem("Todos en la sala");

        JPanel recipientPanel = new JPanel(new BorderLayout());
        recipientPanel.add(recipientLabel, BorderLayout.WEST);
        recipientPanel.add(recipientCombo, BorderLayout.CENTER);

        userPanel.add(new JLabel("Usuarios conectados:"), BorderLayout.NORTH);
        userPanel.add(userScroll, BorderLayout.CENTER);
        userPanel.add(recipientPanel, BorderLayout.SOUTH);

        bottomSplitPane.setLeftComponent(roomPanel);
        bottomSplitPane.setRightComponent(userPanel);
        bottomSplitPane.setDividerLocation(300);

        mainSplitPane.setTopComponent(chatPanel);
        mainSplitPane.setBottomComponent(bottomSplitPane);
        mainSplitPane.setDividerLocation(400);

        frame.add(connectionPanel, BorderLayout.NORTH);
        frame.add(mainSplitPane, BorderLayout.CENTER);

        // Listeners
        connectButton.addActionListener(e -> {
            String server = serverField.getText();
            int port = Integer.parseInt(portField.getText());
            username = usernameField.getText().trim();
            
            if (username.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Ingrese un nombre de usuario", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            try {
                socket = new Socket(server, port);
                dis = new DataInputStream(socket.getInputStream());
                dos = new DataOutputStream(socket.getOutputStream());
                
                // Enviar nombre de usuario
                dos.writeUTF(username);
                dos.flush();
                
                // Iniciar hilo para recibir mensajes
                new Thread(() -> receiveMessages()).start();
                
                connectButton.setEnabled(false);
                disconnectButton.setEnabled(true);
                usernameField.setEnabled(false);
                chatArea.append("Conectado al servidor como " + username + "\n");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(frame, "Error al conectar: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        disconnectButton.addActionListener(e -> disconnect());

        sendButton.addActionListener(e -> sendMessage());

        messageField.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });

        sendFileButton.addActionListener(e -> sendFile());

        createRoomButton.addActionListener(e -> {
            String roomName = newRoomField.getText().trim();
            if (!roomName.isEmpty()) {
                try {
                    dos.writeUTF("JOIN_ROOM");
                    dos.writeUTF(roomName);
                    dos.flush();
                    currentRoom = roomName;
                    newRoomField.setText("");
                } catch (IOException ex) {
                    chatArea.append("Error al unirse a la sala: " + ex.getMessage() + "\n");
                }
            }
        });

        leaveRoomButton.addActionListener(e -> {
            if (currentRoom != null) {
                try {
                    dos.writeUTF("LEAVE_ROOM");
                    dos.flush();
                    currentRoom = null;
                    chatArea.append("Has abandonado la sala\n");
                } catch (IOException ex) {
                    chatArea.append("Error al salir de la sala: " + ex.getMessage() + "\n");
                }
            }
        });

        frame.setVisible(true);
    }

    private static void receiveMessages() {
        try {
            while (true) {
                String message = dis.readUTF();
                String[] parts = message.split(":", 4);
                
                switch (parts[0]) {
                    case "ROOM":
                        chatArea.append("[" + parts[1] + "] " + parts[2] + ": " + parts[3] + "\n");
                        break;
                    case "PRIVATE":
                        chatArea.append("[PRIVADO de " + parts[1] + "]: " + parts[2] + "\n");
                        break;
                    case "JOINED_ROOM":
                        currentRoom = parts[1];
                        chatArea.append("Te has unido a la sala: " + parts[1] + "\n");
                        break;
                    case "LEFT_ROOM":
                        chatArea.append("Has abandonado la sala: " + parts[1] + "\n");
                        currentRoom = null;
                        break;
                    case "USER_LIST":
                        updateUserList(parts[1].split(","));
                        break;
                    case "ROOM_LIST":
                        updateRoomList(parts[1].split(","));
                        break;
                    case "FILE_ROOM":
                        receiveFileNotification(parts[1], parts[2], parts[3], Long.parseLong(parts[4]));
                        break;
                    case "FILE_PRIVATE":
                        receiveFileNotification(null, parts[1], parts[2], Long.parseLong(parts[3]));
                        break;
                }
            }
        } catch (IOException e) {
            chatArea.append("Desconectado del servidor\n");
            disconnect();
        }
    }

    private static void updateUserList(String[] users) {
        SwingUtilities.invokeLater(() -> {
            userListModel.clear();
            recipientCombo.removeAllItems();
            recipientCombo.addItem("Todos en la sala");
            
            for (String user : users) {
                if (!user.equals(username) && !user.isEmpty()) {
                    userListModel.addElement(user);
                    recipientCombo.addItem(user);
                }
            }
        });
    }

    private static void updateRoomList(String[] rooms) {
        SwingUtilities.invokeLater(() -> {
            roomListModel.clear();
            for (String room : rooms) {
                if (!room.isEmpty()) {
                    roomListModel.addElement(room);
                }
            }
        });
    }

    private static void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty() || currentRoom == null) return;
        
        String recipient = (String) recipientCombo.getSelectedItem();
        
        try {
            if (recipient.equals("Todos en la sala")) {
                dos.writeUTF("ROOM_MSG");
                dos.writeUTF(currentRoom);
                dos.writeUTF(message);
                chatArea.append("[Tú en " + currentRoom + "]: " + message + "\n");
            } else {
                dos.writeUTF("PRIVATE_MSG");
                dos.writeUTF(recipient);
                dos.writeUTF(message);
                chatArea.append("[Privado a " + recipient + "]: " + message + "\n");
            }
            dos.flush();
            messageField.setText("");
        } catch (IOException e) {
            chatArea.append("Error al enviar mensaje: " + e.getMessage() + "\n");
        }
    }

    private static void sendFile() {
        if (currentRoom == null) {
            JOptionPane.showMessageDialog(null, "Únete a una sala primero", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            String recipient = (String) recipientCombo.getSelectedItem();

            try {
                if (recipient.equals("Todos en la sala")) {
                    dos.writeUTF("FILE_ROOM");
                    dos.writeUTF(currentRoom);
                    dos.writeUTF(file.getName());
                    dos.writeLong(file.length());
                } else {
                    dos.writeUTF("FILE_PRIVATE");
                    dos.writeUTF(recipient);
                    dos.writeUTF(file.getName());
                    dos.writeLong(file.length());
                }

                // Enviar el archivo
                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[4096];
                int read;
                
                while ((read = fis.read(buffer)) != -1) {
                    dos.write(buffer, 0, read);
                }
                
                fis.close();
                chatArea.append("Archivo enviado: " + file.getName() + "\n");
            } catch (IOException e) {
                chatArea.append("Error al enviar archivo: " + e.getMessage() + "\n");
            }
        }
    }

    private static void receiveFileNotification(String room, String sender, String filename, long size) {
        int option = JOptionPane.showConfirmDialog(null, 
            (room != null ? "[" + room + "] " : "[Privado] ") + sender + " quiere enviarte el archivo " + 
            filename + " (" + size + " bytes). ¿Aceptar?", 
            "Recibir archivo", JOptionPane.YES_NO_OPTION);
        
        if (option == JOptionPane.YES_OPTION) {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setSelectedFile(new File(filename));
            if (fileChooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                try {
                    File file = fileChooser.getSelectedFile();
                    FileOutputStream fos = new FileOutputStream(file);
                    
                    long received = 0;
                    byte[] buffer = new byte[4096];
                    int read;
                    
                    while (received < size && (read = dis.read(buffer, 0, (int) Math.min(buffer.length, size - received))) != -1) {
                        fos.write(buffer, 0, read);
                        received += read;
                    }
                    
                    fos.close();
                    chatArea.append("Archivo recibido: " + filename + "\n");
                } catch (IOException e) {
                    chatArea.append("Error al recibir archivo: " + e.getMessage() + "\n");
                }
            }
        } else {
            try {
                // Descartar el archivo si el usuario no lo quiere
                long skipped = 0;
                byte[] buffer = new byte[4096];
                while (skipped < size) {
                    skipped += dis.read(buffer, 0, (int) Math.min(buffer.length, size - skipped));
                }
            } catch (IOException e) {
                chatArea.append("Error al descartar archivo: " + e.getMessage() + "\n");
            }
        }
    }

    private static void disconnect() {
        try {
            if (socket != null) socket.close();
        } catch (IOException e) {
            // Ignorar
        }
        
        JFrame frame = (JFrame) SwingUtilities.getWindowAncestor(chatArea);
        frame.getContentPane().getComponent(0).getComponent(6).setEnabled(true); // connectButton
        frame.getContentPane().getComponent(0).getComponent(7).setEnabled(false); // disconnectButton
        usernameField.setEnabled(true);
        chatArea.append("Desconectado del servidor\n");
    }
}