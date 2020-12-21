package ca.uhn.fhir.jpa.starter;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;

import ca.uhn.fhir.context.ConfigurationException;

public class ExportData {
    public static final String EXPORT_DATA = "export.json";
    /**
     *
     * @return json loaded from the export.json containing the list of file url for bulk export.
     */
    public static String loadExportData() {
        try {
          InputStream in = ExportData.class.getClassLoader().getResourceAsStream(EXPORT_DATA);
          String text = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
          return text;
        } catch (Exception e) {
            throw new ConfigurationException("Could not load export json", e);
        }
    }
}
