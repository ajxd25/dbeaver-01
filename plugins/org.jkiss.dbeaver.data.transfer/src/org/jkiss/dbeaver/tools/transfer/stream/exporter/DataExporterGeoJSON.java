package org.jkiss.dbeaver.tools.transfer.stream.exporter;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import org.jkiss.dbeaver.model.data.DBDContent;
import org.jkiss.dbeaver.model.data.DBDContentStorage;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporterSite;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.Locale;

public class DataExporterGeoJSON extends StreamExporterAbstract {

    public static final String PROP_PRINT_TABLE_NAME = "printTableName";

    private DBDAttributeBinding[] columns;
    private String tableName;
    private int featureCount = 0;
    private boolean printTableName = true;
    private boolean initialized = false;

    @Override
    public void init(IStreamDataExporterSite site) throws DBException {
        super.init(site);
        printTableName = CommonUtils.getBoolean(site.getProperties().get(PROP_PRINT_TABLE_NAME), true);
        initialized = true;
    }

    @Override
    public void exportHeader(DBCSession session) throws DBException, IOException {
        if (!initialized) {
            throw new DBException("Exporter not initialized. 'init()' was not called.");
        }

        columns = getSite().getAttributes();
        if (columns == null || columns.length == 0) {
            throw new DBException("No columns found for export.");
        }

        tableName = getSite().getSource().getName();
        printHeader();
    }

    private void printHeader() {
        PrintWriter out = getWriter();
        out.write("{\n");
        out.write("\"type\":\"FeatureCollection\",\n");
        // if (printTableName) {
        //     out.write("\"name\":\"" + JSONUtils.escapeJsonString(tableName) + "\",\n");
        // }
        out.write("\"features\":\n[\n");
        featureCount = 0;
    }

    @Override
    public void exportRow(DBCSession session, DBCResultSet resultSet, Object[] row) throws DBException, IOException {
        if (columns == null) {
            throw new DBException("Exporter not initialized: 'columns' is null. Was init() called?");
        }

        PrintWriter out = getWriter();

        if (featureCount > 0) {
            out.write(",\n");
        }
        featureCount++;

        int geomIdx = findGeometryColumnIndex();
        if (geomIdx < 0) {
            throw new DBException("No geometry column detected for GeoJSON export.");
        }

        Object geomValue = row[geomIdx];
        if (DBUtils.isNullValue(geomValue)) {
            writeNullFeature(out);
        } else {
            String geometryString = CommonUtils.toString(geomValue).trim();
            String geoJson;

            if (!geometryString.startsWith("{")) {
                // Handle WKT
                // String[] typeAndCoords = convertWKTtoCoordinatesAndType(geometryString);
                String coords = convertWKTtoCoordinates(geometryString);
                // String geomType = typeAndCoords[0];
                // String coords = typeAndCoords[1];
                // geoJson = "{\"type\":\"" + geomType + "\",\"coordinates\":" + coords + "}";
                geoJson = inferGeometryFromCoordinates(coords);
            } else {
                // Handle embedded GeoJSON
                String coords = extractCoordinatesFromJson(geometryString);
                geoJson = inferGeometryFromCoordinates(coords);
            }

            out.write("    {\n");
            out.write("      \"type\":\"Feature\",\n");
            out.write("      \"geometry\":" + geoJson + ",\n");
            out.write("      \"properties\":{\n");
            writeProperties(out, row, geomIdx);
            out.write("\n      }\n");
            out.write("    }");
        }
    }

    private void writeNullFeature(PrintWriter out) {
        out.write("    {\"type\":\"Feature\",\"geometry\":null,\"properties\":{}}");
    }

    private void writeProperties(PrintWriter out, Object[] row, int skipIndex) {
        boolean firstProp = true;
        for (int i = 0; i < columns.length; i++) {
            if (i == skipIndex) continue;

            DBDAttributeBinding col = columns[i];
            Object val = row[i];
            String key = JSONUtils.escapeJsonString(col.getName());

            if (!firstProp) out.write(",\n");
            firstProp = false;

            out.write("        \"" + key + "\":");
            if (DBUtils.isNullValue(val)) {
                out.write("null");
            } else if (val instanceof Number || val instanceof Boolean) {
                out.write(val.toString());
            } else {
                String text = JSONUtils.escapeJsonString(CommonUtils.toString(val));
                out.write("\"" + text + "\"");
            }
        }
    }

    private int findGeometryColumnIndex() {
        for (int i = 0; i < columns.length; i++) {
            var col = columns[i];
            String typeName = col.getTypeName().toLowerCase(Locale.ROOT);
            if (col.getDataKind() == DBPDataKind.ARRAY ||
                typeName.contains("geometry") ||
                typeName.contains("geography")) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void exportFooter(DBRProgressMonitor monitor) throws IOException {
        PrintWriter out = getWriter();
        out.write("\n  ]\n}\n");
    }

    // ------------------- Utility Methods -----------------------

    private String inferGeometryFromCoordinates(String rawCoords) {
        String coords = rawCoords.trim().replaceAll("\\s+", "");
        try {
            int depth = getArrayDepth(coords);

            switch (depth) {
                case 1:
                    return "{\"type\":\"Point\",\"coordinates\":" + coords + "}";
                case 2:
                    return "{\"type\": \"LineString\",\"coordinates\":" + coords + "}";
                case 3:
                    if (isClosedPolygon(coords)) {
                        return "{\"type\":\"Polygon\",\"coordinates\":" + coords + "}";
                    } else return "{\"type\":\"MultiPolygon\",\"coordinates\":" + "[" + coords + "]" + "}";
                case 4:
                    return "{\"type\":\"MultiPolygon\",\"coordinates\":" + "[" + coords + "]" + "}";
                default:
                    return "null";
            }
        } catch (Exception e) {
            return "null";
        }
    }

    private int getArrayDepth(String coord) {
        int maxDepth = 0, depth = 0;
        for (char c : coord.toCharArray()) {
            if (c == '[') {
                depth++;
                maxDepth = Math.max(maxDepth, depth);
            } else if (c == ']') {
                depth--;
            }
        }
        return maxDepth;
    }

    private boolean isClosedPolygon(String coords) {
        try {
            if (!coords.startsWith("[[[") || !coords.endsWith("]]]")) return false;

            // Remove outermost brackets
            String trimmed = coords.substring(1, coords.length() - 1);

            // Parse coordinate sets manually
            int len = trimmed.length();
            StringBuilder coord = new StringBuilder();
            String[] coordSets = new String[1000]; // max 1000 coordinate pairs
            int coordCount = 0;
            int bracketDepth = 0;

            for (int i = 0; i < len; i++) {
                char c = trimmed.charAt(i);

                if (c == '[') {
                    if (bracketDepth == 1) coord.setLength(0); // start new coordinate
                    bracketDepth++;
                } else if (c == ']') {
                    bracketDepth--;
                    if (bracketDepth == 1) {
                        if (coordCount < coordSets.length) {
                            coordSets[coordCount++] = coord.toString().trim();
                        }
                    }
                } else if (bracketDepth == 2) {
                    coord.append(c);
                }
            }

            if (coordCount < 2) return false;

            String first = coordSets[0];
            String last = coordSets[coordCount - 1];

            if (!first.equals(last)) {
                return false; // First and last not equal => not a closed ring
            }

            // Count how many times first coordinate appears
            int repeatCount = 0;
            for (int i = 0; i < coordCount; i++) {
                if (first.equals(coordSets[i])) {
                    repeatCount++;
                }
            }

            if (repeatCount == 2) {
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }



    private String extractCoordinatesFromJson(String geoJsonStr) {
        int coordIdx = geoJsonStr.indexOf("\"coordinates\"");
        if (coordIdx < 0) return geoJsonStr;

        int start = geoJsonStr.indexOf('[', coordIdx);
        int end = geoJsonStr.lastIndexOf(']');
        return geoJsonStr.substring(start, end + 1);
    }

    private String convertWKTtoCoordinates(String wkt) {
        wkt = wkt.trim();
        int startIdx = wkt.indexOf('(');
        if (startIdx == -1) return "[]";

        String coordPart = wkt.substring(startIdx);
        coordPart = coordPart.replace("(", "[").replace(")", "]");
        coordPart = coordPart.replaceAll(",\\s*", "],[").replaceAll("([\\d\\.\\-]+)\\s+([\\d\\.\\-]+)", "$1,$2");
        return coordPart;
    }
}

// --- Functions for fututre use ---

    // private String[] convertWKTtoCoordinatesAndType(String wkt) {
    //     wkt = wkt.trim();

    //     int firstParen = wkt.indexOf('(');
    //     if (firstParen == -1) return new String[]{"UNKNOWN", "[]"};

    //     String type = wkt.substring(0, firstParen).trim().toUpperCase(Locale.ROOT);
    //     String body = wkt.substring(firstParen);

    //     // Save original body for ring closure check
    //     String rawBody = body.replaceAll("[\\s\\n]+", ""); // strip spaces/newlines

    //     // Convert to brackets for GeoJSON
    //     StringBuilder sb = new StringBuilder();
    //     for (char c : body.toCharArray()) {
    //         if (c == '(') {
    //             sb.append('[');
    //         } else if (c == ')') {
    //             sb.append(']');
    //         } else {
    //             sb.append(c);
    //         }
    //     }

    //     String formatted = sb.toString()
    //         .replaceAll(",\\s+", ",")
    //         .replaceAll("(\\d)\\s+(\\d)", "$1,$2");

    //     if (type.equals("MULTIPOLYGON")) {
    //         int topLevelGroups = countTopLevelPolygons(formatted);
    //         if (topLevelGroups == 1) {
    //             // It's a single polygon, strip one level of brackets
    //             // formatted = formatted.substring(1, formatted.length() - 1);
    //             type = "POLYGON";
    //         } else {
    //             // Ensure it has 4 bracket layers
    //             formatted = "[" + formatted + "]";
    //         }
    //     }

    //     return new String[]{toTitleCaseGeometry(type.toLowerCase(Locale.ROOT)), formatted};
    // }

    // private String capitalizeFirstLetter(String input) {
    //     if (input == null || input.isEmpty()) return input;
    //     return input.substring(0, 1).toUpperCase() + input.substring(1).toLowerCase();
    // }

    // private int countTopLevelPolygons(String coords) {
    //     int count = 0, depth = 0;
    //     for (int i = 0; i < coords.length(); i++) {
    //         char c = coords.charAt(i);
    //         if (c == '[') {
    //             depth++;
    //             if (depth == 2) count++; // only count depth-2: [ [ [ ... ] ] ]
    //         } else if (c == ']') {
    //             depth--;
    //         }
    //     }
    //     return count;
    // }

//     private String toTitleCaseGeometry(String input) {
//         // Handles things like "multipolygon" -> "MultiPolygon"
//         StringBuilder result = new StringBuilder();
//         for (String part : input.split("(?=[A-Z])|_")) {
//             if (part.isEmpty()) continue;
//             result.append(part.substring(0, 1).toUpperCase());
//             if (part.length() > 1) result.append(part.substring(1).toLowerCase());
//         }

//         // Special handling for known geometry types with camel casing
//         switch (result.toString().toLowerCase()) {
//             case "multipolygon": return "MultiPolygon";
//             case "multilinestring": return "MultiLineString";
//             case "multipoint": return "MultiPoint";
//             case "linestring": return "LineString";
//             default: return capitalizeFirstLetter(input);
//         }
//     }
// }