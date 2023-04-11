import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Server {
    private static final int PORT = 12345;

    public static void main(String[] args) throws IOException {
        try {
            ServerSocket serverSocket = new ServerSocket(PORT);
            System.out.println("Server started. Listening on port " + PORT + "...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected: " + clientSocket.getInetAddress());

                String serverDirectory = "C:\\Users\\youne\\IntelliJProjects\\FileSyncTM\\server-files\\";
                new Thread(new ClientHandler(serverSocket, serverDirectory, clientSocket)).start();
            }
        } catch (ConnectException e) {
            System.out.println("Could not start server.");
        }
    }
}

class ClientHandler implements Runnable {
    private final ServerSocket serverSocket;
    private final String serverDirectory;
    private Socket clientSocket;
    private ObjectOutputStream objectOutputStream;
    private ObjectInputStream objectInputStream;

    public ClientHandler(ServerSocket serverSocket, String serverDirectory, Socket clientSocket) throws IOException {
        this.serverSocket = serverSocket;
        this.serverDirectory = serverDirectory;

        this.clientSocket = clientSocket;
        this.objectOutputStream = new ObjectOutputStream(clientSocket.getOutputStream());
        this.objectInputStream = new ObjectInputStream(clientSocket.getInputStream());
    }

    public void run() {
        try {
            clientInputLoop:
            while (true) {
                try {
                    System.out.println("--New loop--");
                    String command = (String)objectInputStream.readObject();
                    String filename = (String)objectInputStream.readObject();

                    System.out.println("Command: " + command);
                    switch (command) {
                        case "LIST":
                            listFiles();
                            System.out.println("Listed files to client " + clientSocket.getInetAddress());
                            break;
                        case "UPLOAD":
                            downloadFile(filename);
                            System.out.println("Uploaded file '" + filename + "' to client " + clientSocket.getInetAddress());
                            break;
                        case "DOWNLOAD":
                            uploadFile(filename);
                            System.out.println("Downloaded file '" + filename + "' from client " + clientSocket.getInetAddress());
                            break;
                        case "CREATE_FOLDER":
                            createFolder(filename);
                            System.out.println("Created folder '" + filename + "' from client " + clientSocket.getInetAddress());
                            break;
                        case "DELETE":
                            deleteFile(filename);
                            System.out.println("Deleted '" + filename + "' from client " + clientSocket.getInetAddress());
                            break;
                        case "DONE":
                            System.out.println("File synchronization complete for client " + clientSocket.getInetAddress());
                            clientSocket.close();
                            objectInputStream.close();
                            objectOutputStream.close();

                            break clientInputLoop;
                    }
                } catch (SocketException e) {
                    System.out.println("Client disconnected: " + clientSocket.getInetAddress());
                    break;
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void listFiles() {
        ArrayList<File> serverFiles = getFiles(new File(serverDirectory));
        for (File serverFile : serverFiles) {
            try {
                String localPath = serverFile.getAbsolutePath();
                localPath = localPath.replace(this.serverDirectory, "");

                String typePrefix = serverFile.isDirectory()
                        ? "DIR"
                        : "FILE";

                objectOutputStream.writeObject(typePrefix + " " + localPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            objectOutputStream.writeObject("LIST_END");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void downloadFile(String filename) throws IOException {
        String filePath = serverDirectory + filename;
        FileOutputStream fileOutputStream = new FileOutputStream(filePath);

        byte[] buffer = new byte[clientSocket.getReceiveBufferSize()];
        int bytesRead;

        while ((bytesRead = objectInputStream.read(buffer)) != -1) {
            fileOutputStream.write(buffer, 0, bytesRead);
        }

        fileOutputStream.close();
    }

    private void uploadFile(String filename) throws IOException {
        String filePath = serverDirectory + filename;
        FileInputStream fileInputStream = new FileInputStream(filePath);

        byte[] buffer = new byte[clientSocket.getReceiveBufferSize()];
        int bytesRead;
        while ((bytesRead = fileInputStream.read(buffer)) != -1) {
            objectOutputStream.write(buffer, 0, bytesRead);
        }

        fileInputStream.close();
        objectOutputStream.flush();

        reinitializeConnection();
    }

    private void createFolder(String folder) {
        String filePath = serverDirectory + folder;
        File file = new File(filePath);

        if (!file.exists() && !file.mkdirs()) {
            System.out.println("Failed to create folder " + folder);
        }
    }

    private void deleteFile(String filename) {
        String filePath = serverDirectory + filename;
        File file = new File(filePath);
        if (!file.delete()) {
            System.out.println("Failed to delete file " + filename);
        }
    }

    public ArrayList<File> getFiles(File directory) {
        ArrayList<File> clientFiles = new ArrayList<>();
        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                clientFiles.add(file);
                if (file.isDirectory()) {
                    clientFiles.addAll(getFiles(file));
                }
            }
        }

        Collections.sort(clientFiles);
        return clientFiles;
    }

    private void reinitializeConnection() throws IOException {
        objectOutputStream.close();
        objectInputStream.close();
        clientSocket.close();
        clientSocket = serverSocket.accept();
        objectOutputStream = new ObjectOutputStream(clientSocket.getOutputStream());
        objectInputStream = new ObjectInputStream(clientSocket.getInputStream());
    }
}