package org.jkiss.dbeaver.tools.transfer.stream.exporter;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporterSite;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.ArrayUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

/**
 * GeoJSON Exporter
 */
public class DataExporterGeoJSON extends StreamExporterAbstract {

    public static final String PROP_PRINT_TABLE_NAME = "printTableName";

    private DBDAttributeBinding[] columns;
    private String tableName;
    private int featureCount = 0;
    private boolean printTableName = true;

    @Override
    public void init(IStreamDataExporterSite site) throws DBException {
        super.init(site);
        printTableName = CommonUtils.getBoolean(site.getProperties().get(PROP_PRINT_TABLE_NAME), true);
    }

    @Override
    public void exportHeader(DBCSession session) throws DBException, IOException {
        columns = getSite().getAttributes();
        tableName = getSite().getSource().getName();
        printHeader();
    }

    private void printHeader() {
        PrintWriter out = getWriter();
        out.write("{\n");
        out.write("  \"type\": \"FeatureCollection\",\n");
        if (printTableName) {
            out.write("  \"name\": \"" + JSONUtils.escapeJsonString(tableName) + "\",\n");
        }
        out.write("  \"features\": [\n");
        featureCount = 0;
    }

    @Override
    public void exportRow(DBCSession session, DBCResultSet resultSet, Object[] row) throws DBException, IOException {
        PrintWriter out = getWriter();

        if (featureCount > 0) {
            out.write(",\n");
        }
        featureCount++;

        // Identify geometry column – first column with spatial type
        int geomIdx = findGeometryColumnIndex();
        if (geomIdx < 0) {
            throw new DBException("No geometry column detected for GeoJSON export");
        }

        Object geomValue = row[geomIdx];
        if (DBUtils.isNullValue(geomValue)) {
            writeNullFeature(out);
        } else {
            String geoJson = geomValue.toString(); // Expect ST_AsGeoJSON output or DB client
            out.write("    { \"type\": \"Feature\", \n");
            out.write("      \"geometry\": " + geoJson + ",\n");
            out.write("      \"properties\": {");
            writeProperties(out, row, geomIdx);
            out.write("      }\n");
            out.write("    }");
        }
    }

    private void writeNullFeature(PrintWriter out) {
        out.write("    { \"type\": \"Feature\", \"geometry\": null, \"properties\": {} }");
    }

    private void writeProperties(PrintWriter out, Object[] row, int skipIndex) {
        boolean firstProp = true;
        for (int i = 0; i < columns.length; i++) {
            if (i == skipIndex) continue;
            DBDAttributeBinding col = columns[i];
            String key = JSONUtils.escapeJsonString(col.getName());
            Object val = row[i];
            if (!firstProp) {
                out.write(", ");
            }
            firstProp = false;
            out.write("\"" + key + "\": ");
            if (DBUtils.isNullValue(val)) {
                out.write("null");
            } else {
                // Basic scalar types
                if (val instanceof Number || val instanceof Boolean) {
                    out.write(val.toString());
                } else {
                    String text = CommonUtils.toString(val);
                    out.write("\"" + JSONUtils.escapeJsonString(text) + "\"");
                }
            }
        }
    }

    private int findGeometryColumnIndex() {
        for (int i = 0; i < columns.length; i++) {
            var col = columns[i];
            if (col.getDataKind() == DBPDataKind.ARRAY // spatial types may appear ARRAY-like
                || col.getTypeName().toLowerCase(Locale.ROOT).contains("geometry")
                || col.getTypeName().toLowerCase(Locale.ROOT).contains("geography")) {
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
}
