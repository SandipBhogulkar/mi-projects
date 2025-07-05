package com.tascua.reader;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONObject;
import org.apache.synapse.MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import com.jcraft.jsch.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

public class ExcelReader extends AbstractMediator {

    public boolean mediate(MessageContext context) {
        // SFTP server details
        String sftpHost = "10.10.16.170";  // Update to the actual IP/hostname of your SFTP server
        int sftpPort = 22;  // Use the standard SFTP port (22)
        String sftpUsername = (String) context.getProperty("sftpUsername");
        String sftpPassword = (String) context.getProperty("sftpPassword");
        String remoteFilePath = (String) context.getProperty("remoteFilePath");

        // Get the column names property, if not present, use original headers
        String columnNames = (String) context.getProperty("columnNames");
        String[] headersArray = null;

        if (columnNames != null && !columnNames.isEmpty()) {
            headersArray = columnNames.split(",");  // Split the comma-separated header names
        }

        try {
            // Fetch the file from SFTP and process it directly
            InputStream inputStream = fetchFileFromSFTP(sftpHost, sftpPort, sftpUsername, sftpPassword, remoteFilePath);
            if (inputStream != null) {
                // Process the Excel file and convert to JSON format
                String jsonOutput = readExcelFile(inputStream, headersArray);
                context.setProperty("jsonOutput", jsonOutput);
                log.info("Converted JSON Data: " + jsonOutput); // You can log or return the JSON data as needed
            } else {
                log.error("Failed to fetch the file from the SFTP server.");
                return false; // Indicating error in mediation
            }
        } catch (Exception e) {
            log.error("Error while fetching or processing the Excel file", e);
            return false;  // Indicating error in mediation
        }
        return true;
    }

    // Method to read Excel file directly from InputStream and convert to JSON format
    private String readExcelFile(InputStream inputStream, String[] headersArray) throws IOException {
        Workbook workbook = new XSSFWorkbook(inputStream);
        JSONArray jsonArray = new JSONArray();

        // Loop through all sheets in the Excel file
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            log.info("Reading sheet: " + sheet.getSheetName());

            // Get the first row (header row) to use as keys in the JSON
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                log.warn("Header row is empty or missing in sheet: " + sheet.getSheetName());
                continue;
            }

            // Create an iterator for the header row (keys for the JSON)
            Iterator<Cell> headerIterator = headerRow.iterator();
            JSONArray sheetData = new JSONArray();

            // Loop through all rows (skipping the header row)
            for (int j = 1; j < sheet.getPhysicalNumberOfRows(); j++) {
                Row row = sheet.getRow(j);
                if (row == null) {
                    continue;  // Skip empty rows
                }

                // Start over with a fresh header iterator for each row
                headerIterator = headerRow.iterator(); // Reinitialize headerIterator for each row
                JSONObject jsonObject = new JSONObject();
                int cellIndex = 0;

                // Loop through all cells in the row (using headersArray for keys if passed, else use Excel headers)
                while (headerIterator.hasNext() && cellIndex < row.getPhysicalNumberOfCells()) {
                    Cell headerCell = headerIterator.next();
                    String headerKey = headerCell.toString();

                    // Use the passed headersArray if it exists, otherwise use the header from Excel
                    String jsonKey = (headersArray != null && headersArray.length > cellIndex) ? headersArray[cellIndex] : headerKey;

                    // Handle null or empty cells
                    Cell dataCell = row.getCell(cellIndex);
                    String cellValue = (dataCell != null) ? dataCell.toString() : "";

                    // Put the value in the JSON object with the mapped key
                    jsonObject.put(jsonKey, cellValue);
                    cellIndex++;
                }

                // Add the row's JSON object to the sheet data array
                sheetData.put(jsonObject);
            }

            // Add the sheet data to the overall JSON array
            jsonArray.put(new JSONObject().put(sheet.getSheetName(), sheetData));
        }

        workbook.close();
        inputStream.close();  // Don't forget to close the input stream

        // Convert the JSONArray to a string and return it
        return jsonArray.toString();
    }

    // Method to fetch the file from SFTP using JSch
    private InputStream fetchFileFromSFTP(String sftpHost, int sftpPort, String sftpUser, String sftpPassword, String remoteFilePath) throws JSchException, SftpException, IOException {
        Session session = null;
        Channel channel = null;
        ChannelSftp sftpChannel = null;
        ByteArrayOutputStream byteArrayOutputStream = null;
        InputStream inputStream = null;

        try {
            // Initialize JSch session
            JSch jsch = new JSch();
            session = jsch.getSession(sftpUser, sftpHost, sftpPort);  // Connect to the remote SFTP server
            session.setPassword(sftpPassword);
            session.setConfig("StrictHostKeyChecking", "no");

            session.setTimeout(60000); // Set a timeout of 60 seconds for the session
            session.connect();  // Connect to the session

            // Open SFTP channel
            channel = session.openChannel("sftp");
            channel.connect();
            sftpChannel = (ChannelSftp) channel;

            log.info("Successfully connected to SFTP server: " + sftpHost);

            // Prepare a ByteArrayOutputStream to capture the file
            byteArrayOutputStream = new ByteArrayOutputStream();
            sftpChannel.get(remoteFilePath, byteArrayOutputStream); // Fetch the file into the ByteArrayOutputStream
            log.info("File fetched successfully from SFTP server: " + remoteFilePath);

            // Convert ByteArrayOutputStream to InputStream
            inputStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray());

        } catch (SftpException e) {
            log.error("SFTP exception occurred while fetching the file", e);
            throw e;
        } catch (JSchException e) {
            log.error("JSch exception occurred while connecting to the SFTP server", e);
            throw e;
        } finally {
            // Ensure the SFTP channel and session are closed correctly
            if (sftpChannel != null && sftpChannel.isConnected()) {
                try {
                    sftpChannel.exit();
                } catch (Exception e) {
                    log.error("Error while closing SFTP channel", e);
                }
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
        return inputStream;
    }
}
