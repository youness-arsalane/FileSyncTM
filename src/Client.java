import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class Client {
    private static final int SERVER_PORT = 5656;

    public static void main(String[] args) throws Exception {
        Client client = new Client();
        client.start();
    }

    public void start() throws Exception {
        System.out.println("Enter your working directory:");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String clientDirectory = reader.readLine();

        clientDirectory = clientDirectory.replace("/", "\\");
        if (!clientDirectory.endsWith("\\")) {
            clientDirectory += "\\";
        }

        File clientDirectoryFile = new File(clientDirectory);

        if (!clientDirectoryFile.exists()) {
            throw new Exception("Path not found.");
        } else if (!clientDirectoryFile.isDirectory()) {
            throw new Exception("Path is not a directory.");
        }

        System.out.println("Path is validated.");

        try {
            System.out.println("Connecting to server...");
            Socket socket = new Socket("localhost", SERVER_PORT);
            System.out.println("Successfully connected to server!");

            new Thread(new ServerThread(socket, clientDirectory)).start();
        } catch (ConnectException e) {
            System.out.println("Could not connect to server.");
        }
    }
}

class ServerThread implements Runnable {
    private final int intervalSeconds = 5;
    private final String directory;
    private final ArrayList<String> clientFiles;
    private Socket clientSocket;
    private ObjectInputStream objectInputStream;
    private ObjectOutputStream objectOutputStream;

    public ServerThread(Socket clientSocket, String directory) throws IOException {
        this.clientSocket = clientSocket;
        this.directory = directory;
        this.objectOutputStream = new ObjectOutputStream(clientSocket.getOutputStream());
        this.objectInputStream = new ObjectInputStream(clientSocket.getInputStream());
        this.clientFiles = getClientFiles(new File(directory));
    }

    @Override
    public void run() {
        try {
            compareServerFiles();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        while (true) {
            try {
                checkForChanges();
                TimeUnit.SECONDS.sleep(intervalSeconds);
            } catch (SocketException e) {
                System.out.println("Lost connection with server");
                break;
            } catch (IOException | InterruptedException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    private ArrayList<String> getStackedChanges() throws IOException, ClassNotFoundException {
        objectOutputStream.writeObject("STACKED_CHANGES");
        objectOutputStream.writeObject("");

        ArrayList<String> stackedChanges = new ArrayList<>();
        while (true) {
            String filenameData = (String) objectInputStream.readObject();
            if (filenameData.equals("STACKED_CHANGES_END")) {
                break;
            }

            stackedChanges.add(filenameData);
        }

        if (stackedChanges.size() > 0) {
            System.out.println("Stacked changes:");
            System.out.println(stackedChanges);
        }

        return stackedChanges;
    }

    private void compareServerFiles() throws IOException, ClassNotFoundException {
        objectOutputStream.writeObject("LIST");
        objectOutputStream.writeObject("");

        ArrayList<String> serverFilenamesData = new ArrayList<>();
        while (true) {
            String filenameData = (String) objectInputStream.readObject();
            if (filenameData.equals("LIST_END")) {
                break;
            }

            serverFilenamesData.add(filenameData);
        }

        ArrayList<String> serverFilenames = new ArrayList<>();
        for (String filenameData : serverFilenamesData) {
            String[] filenameArguments = filenameData.split(" ");
            String typePrefix = filenameArguments[0];
            String filename = filenameArguments[1];

            serverFilenames.add(filename);

            File file = new File(directory + filename);
            if (file.exists()) {
                continue;
            }

            clientFiles.add(filename);
            switch (typePrefix) {
                case "FILE":
                    downloadFile(filename);
                    break;
                case "DIR":
                    file.mkdir();
                    break;
            }
        }

        for (String clientFile : clientFiles) {
            if (serverFilenames.contains(clientFile)) {
                continue;
            }

            File file = new File(directory + clientFile);
            if (file.isDirectory()) {
                createFolder(clientFile);
            } else {
                try {
                    uploadFile(clientFile);
                } catch (FileNotFoundException e) {
                }
            }
        }
    }

    private void checkForChanges() throws IOException, ClassNotFoundException {
        List<String> toAdd = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();

        ArrayList<String> newClientFiles = getClientFiles(new File(directory));
        clientFiles.sort(Collections.reverseOrder());

        for (String filename : clientFiles) {
            if (!newClientFiles.contains(filename)) {
                deleteFile(filename);
                toDelete.add(filename);
            }
        }

        Collections.sort(clientFiles);
        for (String filename : newClientFiles) {
            File file = new File(directory + filename);

            if (!clientFiles.contains(filename)) {
                if (file.isDirectory()) {
                    createFolder(filename);
                } else {
                    try {
                        uploadFile(filename);
                    } catch (FileNotFoundException e) {
                        continue;
                    }
                }

                toAdd.add(filename);
            } else if (!file.isDirectory()) {
                long lastModified = file.lastModified();
                Date currentDate = new java.util.Date();

                if (currentDate.getTime() - (intervalSeconds * 1000) <= lastModified) {
                    try {
                        uploadFile(filename);
                    } catch (FileNotFoundException e) {
                    }
                }
            }
        }

        clientFiles.addAll(toAdd);
        clientFiles.removeAll(toDelete);
        Collections.sort(clientFiles);

        ArrayList<String> stackedChanges = getStackedChanges();
        for (String stackedChange : stackedChanges) {
            String[] stackedChangeArgs = stackedChange.split(" ");
            String command = stackedChangeArgs[0];
            String filename = stackedChangeArgs[1];

            File file = new File(directory + filename);
            switch (command) {
                case "DOWNLOAD":
                    downloadFile(filename);
                    clientFiles.add(filename);
                    break;
                case "CREATE_FOLDER":
                    file.mkdir();
                    break;
                case "DELETE":
                    file.delete();
                    break;
            }
        }
    }

    private void downloadFile(String filename) throws IOException {
        objectOutputStream.writeObject("DOWNLOAD");
        objectOutputStream.flush();
        objectOutputStream.writeObject(filename);
        objectOutputStream.flush();

        String filePath = directory + filename;
        FileOutputStream fileOutputStream = new FileOutputStream(filePath);

        byte[] buffer = new byte[clientSocket.getReceiveBufferSize()];
        int bytesRead;

        long size = objectInputStream.readLong();
        while (size > 0 && (bytesRead = objectInputStream.read(buffer, 0, (int) Math.min(buffer.length, size))) != -1) {
            fileOutputStream.write(buffer, 0, bytesRead);
            size -= bytesRead;
        }

        fileOutputStream.flush();
        fileOutputStream.close();

        System.out.println("Downloaded file '" + filename + "' from server.");
    }

    private void uploadFile(String filename) throws IOException {
        String filePath = directory + filename;
        File file = new File(filePath);

        FileInputStream fileInputStream = new FileInputStream(filePath);

        objectOutputStream.writeObject("UPLOAD");
        objectOutputStream.flush();
        objectOutputStream.writeObject(filename);
        objectOutputStream.flush();

        byte[] buffer = new byte[clientSocket.getReceiveBufferSize()];
        float filesize = file.length();
        float chunks = filesize / (float) buffer.length;
        if (chunks < 1.0F) {
            chunks = 1.0F;
        }
        int bytesRead;
        int chunkIndex = 0;

        objectOutputStream.writeLong(file.length());

        while ((bytesRead = fileInputStream.read(buffer)) != -1) {
            objectOutputStream.write(buffer, 0, bytesRead);
            objectOutputStream.flush();
            chunkIndex++;

            float percentage = chunkIndex / chunks * 100;
            progressBar((int) percentage);
        }

        System.out.println('\n');

        fileInputStream.close();

        System.out.println("Uploaded file '" + filename + "' to server.");
    }

    private void createFolder(String folder) throws IOException {
        objectOutputStream.writeObject("CREATE_FOLDER");
        objectOutputStream.writeObject(folder);

        System.out.println("Created folder '" + folder + "' from client.");
    }

    private void deleteFile(String filename) throws IOException {
        objectOutputStream.writeObject("DELETE");
        objectOutputStream.writeObject(filename);

        File file = new File(directory + filename);
        file.delete();

        System.out.println("Deleted file '" + filename + "' from client.");
    }

    public ArrayList<String> getClientFiles(File directory) {
        ArrayList<String> clientFiles = new ArrayList<>();
        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                String localPath = file.getAbsolutePath();
                localPath = localPath.replace(this.directory, "");

                clientFiles.add(localPath);
                if (file.isDirectory()) {
                    clientFiles.addAll(getClientFiles(file));
                }
            }
        }

        Collections.sort(clientFiles);
        return clientFiles;
    }

    public static void progressBar(int percentage) {
        int length = 26;
        int filledLength = (int) (percentage / 100.0 * length);
        int remainingLength = length - filledLength;

        String sb = '\r' +
                String.format("%3d%% [", percentage) +
                "=".repeat(Math.max(0, filledLength)) +
                " ".repeat(Math.max(0, remainingLength)) +
                ']';

        System.out.print(sb);
    }
}