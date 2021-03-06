/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.services.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileUtils {
    private static final Logger log = LoggerFactory.getLogger(FileUtils.class);

    /**
     * Read serialized object from a file
     * 
     * @param name
     * @return
     * @throws ClassNotFoundException
     * @throws IOException
     */
    public static Object readObjectFromFile(String name)
            throws ClassNotFoundException, IOException {
        byte[] data = readDataFromFile(name);
        return deserialize(data);
    }

    /**
     * Write serialized object into a file
     * 
     * @param obj
     * @param name
     * @throws IOException
     */
    public static void writeObjectToFile(Object obj, String name) throws IOException {
        File file = new File(name);
        // if file doesn't exists, then create it
        if (!file.exists()) {
            File dir = new File(file.getParentFile().getAbsolutePath());
            if (!dir.exists()) {
                dir.mkdirs();
            }
            file.createNewFile();
        }

        byte[] data = serialize(obj);
        try (FileOutputStream fop = new FileOutputStream(file)) {
            fop.write(data);
            fop.flush();
            fop.close();
        } catch (IOException e) {
        	log.error(e.getMessage(), e);
        }
    }

    private static byte[] readDataFromFile(String name) throws IOException {
        Path path = Paths.get(name);
        return Files.readAllBytes(path);
    }

    private static byte[] serialize(Object o) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        try {
            out.writeObject(o);
        } finally {
            out.close();
        }
        return bos.toByteArray();
    }

    private static Object deserialize(byte[] data) throws IOException,
            ClassNotFoundException {
        Object obj = null;
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ObjectInputStream in = new ObjectInputStream(bis);
        try {
            obj = in.readObject();
        } finally {
            in.close();
        }
        return obj;
    }

    /**
     * Write byte array into a regular file
     * 
     * @param filePath
     * @param content
     * @throws IOException
     */
    public static void writePlainFile(String filePath, byte[] content) throws IOException {
        FileOutputStream fileOuputStream = new FileOutputStream(filePath);
        fileOuputStream.write(content);
        fileOuputStream.close();
    }

    /**
     * check if a file exists.
     * 
     * @param filepath
     * @return
     */
    public static boolean exists(String filepath) {
        File f = new File(filepath);
        if (f.exists()) {
            return true;
        }
        return false;
    }

    /**
     * Delete a file
     * 
     * @param filePath
     * @throws IOException
     */
    public static void deleteFile(String filePath) throws IOException {
        try {
            File file = new File(filePath);
            file.delete();
        } catch (Exception e) {
            log.error("Failed to delete {}.", filePath, e);
        }
    }

    /**
     * Get the value of property with specific key
     * 
     * @param file
     * @param key
     * @return value of the key
     * @throws IOException
     */
    public static String readValueFromFile(File file, String key) throws IOException {
        String value = null;
        if (file.exists()) {
            Properties prop = new Properties();
            FileReader reader = new FileReader(file);
            prop.load(reader);
            reader.close();
            value = prop.getProperty(key);
            log.info("The value of property with key({}) is: {}", key, value);
            return value;
        }
        log.info("File({}) doesn't exist", file.getAbsoluteFile());
        return null;
    }

    public static void chmod(File file, String perms) {
        if (file == null || file.exists() == false) {
            return;
        }
        String[] cmds = { "/bin/chmod", "-R", perms, file.getAbsolutePath() };
        Exec.Result result = Exec.exec(Exec.DEFAULT_CMD_TIMEOUT, cmds);
        if (result.execFailed() || result.getExitValue() != 0) {
            throw new IllegalStateException(String.format("Execute command failed: %s", result));
        }
    }

    public static void chown(File file, String owner, String group) {
        if (file == null || file.exists() == false) {
            return;
        }
        String[] cmds = { "/bin/chown", "-R", owner + ":" + group, file.getAbsolutePath() };
        Exec.Result result = Exec.exec(Exec.DEFAULT_CMD_TIMEOUT, cmds);
        if (result.execFailed() || result.getExitValue() != 0) {
            throw new IllegalStateException(String.format("Execute command failed: %s", result));
        }
    }
}
