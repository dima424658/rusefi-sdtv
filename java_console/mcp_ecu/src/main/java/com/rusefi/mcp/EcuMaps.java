package com.rusefi.mcp;

import com.opensr5.ini.IniFileModel;
import com.opensr5.ini.TableModel;
import com.opensr5.ini.field.ArrayIniField;
import com.opensr5.ini.field.IniField;
import com.rusefi.binaryprotocol.BinaryProtocol;
import com.rusefi.config.FieldType;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("unchecked")
final class EcuMaps {
    // Accessed only on the link communication executor.
    private final Map<String, MapWrite> writes = new LinkedHashMap<>();
    private BinaryProtocol connection;

    private static final class MapWrite {
        final TableModel table;
        final ArrayIniField field;
        final byte[] before;
        final byte[] after;
        final String axisRevision;
        boolean restoredAny;

        MapWrite(TableModel table, ArrayIniField field, byte[] before, byte[] after, String axisRevision) {
            this.table = table;
            this.field = field;
            this.before = before;
            this.after = after;
            this.axisRevision = axisRevision;
        }
    }

    static JSONObject list(IniFileModel ini, String signature) {
        JSONArray maps = new JSONArray();
        for (TableModel table : ini.getTables().values()) {
            ArrayIniField field = array(ini, table.getZBinsConstant());
            if (field != null) {
                maps.add(describe(table, field));
            }
        }
        return object("success", true, "signature", signature, "maps", maps);
    }

    JSONObject access(BinaryProtocol bp, IniFileModel ini, JSONObject args, String operation) {
        if (connection != bp) {
            writes.clear();
            connection = bp;
        }
        if ("rollback_map_write".equals(operation)) {
            return rollback(bp, ini, args);
        }
        Object id = args.get("id");
        TableModel table = id instanceof String ? ini.getTable((String) id) : null;
        if (table == null) {
            throw new IllegalArgumentException("Supply a table id from list_maps");
        }
        ArrayIniField field = array(ini, table.getZBinsConstant());
        if (field == null) {
            throw new IllegalArgumentException("Map has no readable numeric calibration array: " + id);
        }

        JSONObject result = describe(table, field);
        result.put("signature", bp.signature);
        byte[] current = read(bp, field);
        boolean write = "write_map".equals(operation);
        boolean preview = "preview_map_write".equals(operation);
        Object expectedRevision = args.get("expectedRevision");
        if (write && args.containsKey("expectedRevision")
                && (!(expectedRevision instanceof String) || !((String) expectedRevision).matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("expectedRevision must be a revision returned by the map tools");
        }
        ArrayIniField x = array(ini, table.getXBinsConstant());
        ArrayIniField y = array(ini, table.getYBinsConstant());
        byte[] xBytes = x == null ? null : read(bp, x);
        byte[] yBytes = y == null ? null : read(bp, y);
        String axisRevision = revision(bp.signature, table.getTableId(), new byte[0], xBytes, yBytes);
        String revision = revision(bp.signature, table.getTableId(), current, xBytes, yBytes);
        if (write && expectedRevision != null && !revision.equals(expectedRevision)) {
            return object("success", false, "error", "Map revision changed. Read or preview the map again.",
                    "id", table.getTableId(), "revision", revision);
        }
        result.put("revision", revision);
        if (!write) {
            result.put("xBins", axis(ini, x, xBytes));
            result.put("yBins", axis(ini, y, yBytes));
        }
        if (write || preview) {
            byte[] expected = current.clone();
            Patch patch = applyPatch(ini, field, args, expected);
            result.put("changed", !Arrays.equals(current, expected));
            if (write) {
                update(bp, ini, field, args, current, expected, patch, result);
                if (Boolean.TRUE.equals(result.get("changed"))) {
                    String writeId = UUID.randomUUID().toString();
                    writes.put(writeId, new MapWrite(table, field, current, expected, axisRevision));
                    if (writes.size() > 32) {
                        writes.remove(writes.keySet().iterator().next());
                    }
                    result.put("writeId", writeId);
                }
                // The input revision describes the pre-write snapshot.
                result.remove("revision");
            } else {
                result.put("row", patch.row);
                result.put("column", patch.column);
                result.put("currentValues", patchValues(ini, field, current, patch));
                result.put("requestedValues", args.get("values"));
                result.put("effectiveValues", patchValues(ini, field, expected, patch));
            }
        } else {
            result.put("values", decode(ini, field, current));
        }
        result.put("success", true);
        return result;
    }

    private JSONObject rollback(BinaryProtocol bp, IniFileModel ini, JSONObject args) {
        Object id = args.get("writeId");
        MapWrite write = writes.get(id);
        if (write == null) {
            throw new IllegalArgumentException("Unknown writeId for this ECU connection");
        }
        boolean apply = flag(args, "apply");
        boolean persist = flag(args, "persist");
        if (persist && !apply) {
            throw new IllegalArgumentException("persist requires apply=true");
        }
        ArrayIniField x = array(ini, write.table.getXBinsConstant());
        ArrayIniField y = array(ini, write.table.getYBinsConstant());
        String axes = revision(bp.signature, write.table.getTableId(), new byte[0],
                x == null ? null : read(bp, x), y == null ? null : read(bp, y));
        if (!write.axisRevision.equals(axes)) {
            return object("success", false, "error", "Map axis bins changed since the original write",
                    "writeId", id);
        }

        ArrayIniField field = write.field;
        byte[] snapshot = apply ? null : read(bp, field);
        JSONArray eligible = new JSONArray();
        JSONArray conflicts = new JSONArray();
        JSONArray unchanged = new JSONArray();
        int size = field.getType().getStorageSize();
        try {
            for (int offset = 0; offset < field.getSize(); offset += size) {
                ByteBuffer before = ByteBuffer.wrap(write.before, offset, size);
                ByteBuffer after = ByteBuffer.wrap(write.after, offset, size);
                if (before.equals(after)) {
                    continue;
                }
                int ecuOffset = field.getOffset() + offset;
                ByteBuffer current = apply ? readCell(bp, field, ecuOffset, size)
                        : ByteBuffer.wrap(snapshot, offset, size);
                JSONObject cell = rollbackCell(ini, field, offset, size, before, after, current);
                if (current.equals(before)) {
                    unchanged.add(cell);
                } else if (!current.equals(after)) {
                    conflicts.add(cell);
                } else {
                    if (apply) {
                        bp.writeInBlocks(write.before, offset, ecuOffset, size, field.getPageIndex());
                        if (!before.equals(readCell(bp, field, ecuOffset, size))) {
                            throw new IllegalStateException("Rollback readback differs at ECU offset " + ecuOffset);
                        }
                        write.restoredAny = true;
                    }
                    eligible.add(cell);
                }
            }
            boolean burn = persist && write.restoredAny;
            if (burn) {
                burn(bp, ini, field);
            }
            if (apply) {
                writes.remove(id);
            }
            return object("success", true, "writeId", id, "id", write.table.getTableId(),
                    "page", field.getPageIndex(),
                    "applied", apply, "complete", conflicts.isEmpty(), "burnAcknowledged", burn,
                    apply ? "restoredCells" : "eligibleCells", eligible,
                    "conflicts", conflicts, "alreadyRestored", unchanged);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Rollback could not finish. ECU RAM may contain a partial rollback: "
                    + e.getMessage(), e);
        }
    }

    private static ByteBuffer readCell(BinaryProtocol bp, ArrayIniField field, int offset, int size) {
        byte[] data = bp.readFromPage(field.getPageIndex(), offset, size);
        if (data == null || data.length != size) {
            throw new IllegalStateException("ECU cell read failed at offset " + offset);
        }
        return ByteBuffer.wrap(data);
    }

    private static JSONObject rollbackCell(IniFileModel ini, ArrayIniField field, int offset, int size,
                                           ByteBuffer before, ByteBuffer after, ByteBuffer current) {
        int index = offset / size;
        return object("row", index / field.getCols(), "column", index % field.getCols(),
                "current", cellValue(ini, field, current),
                "written", cellValue(ini, field, after),
                "restore", cellValue(ini, field, before));
    }

    private static Double cellValue(IniFileModel ini, ArrayIniField field, ByteBuffer bytes) {
        double decoded = field.getType().readRawValue(bytes.duplicate().order(ini.getByteOrder())) * field.getMultiplier();
        return Double.isFinite(decoded) ? decoded : null;
    }

    private static boolean flag(JSONObject args, String name) {
        Object value = args.getOrDefault(name, false);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return (Boolean) value;
    }

    private static JSONObject describe(TableModel table, ArrayIniField field) {
        return object("id", table.getTableId(), "title", table.getTitle(),
                "field", field.getName(), "page", field.getPageIndex(), "rows", field.getRows(), "columns", field.getCols(),
                "units", field.getUnits(), "scale", field.getMultiplier(),
                "min", field.getMin(), "max", field.getMax(),
                "xField", table.getXBinsConstant(), "yField", table.getYBinsConstant());
    }

    private static ArrayIniField array(IniFileModel ini, String name) {
        IniField field = name == null ? null : ini.findIniField(name).orElse(null);
        if (field instanceof ArrayIniField) {
            ArrayIniField array = (ArrayIniField) field;
            if (array.getType().isNumeric() && Double.isFinite(array.getMultiplier()) && array.getMultiplier() != 0) {
                return array;
            }
        }
        return null;
    }

    private static byte[] read(BinaryProtocol bp, ArrayIniField field) {
        byte[] data = bp.readFromPage(field.getPageIndex(), field.getOffset(), field.getSize());
        if (data == null || data.length != field.getSize()) {
            throw new IllegalStateException("ECU read failed for " + field.getName());
        }
        return data;
    }

    private static String revision(String signature, String tableId, byte[] cells, byte[] x, byte[] y) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : new byte[][]{signature.getBytes(StandardCharsets.UTF_8),
                    tableId.getBytes(StandardCharsets.UTF_8), cells, x, y}) {
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(part == null ? -1 : part.length).array());
                if (part != null) {
                    digest.update(part);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b & 0xff) >>> 4, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static JSONArray axis(IniFileModel ini, ArrayIniField field, byte[] bytes) {
        if (field == null) {
            return null;
        }
        JSONArray rows = decode(ini, field, bytes);
        JSONArray values = new JSONArray();
        for (Object row : rows) {
            values.addAll((JSONArray) row);
        }
        return values;
    }

    private static JSONArray decode(IniFileModel ini, ArrayIniField field, byte[] bytes) {
        return patchValues(ini, field, bytes, new Patch(0, 0, field.getRows(), field.getCols()));
    }

    private static JSONArray patchValues(IniFileModel ini, ArrayIniField field, byte[] bytes, Patch patch) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ini.getByteOrder());
        JSONArray rows = new JSONArray();
        for (int r = patch.row; r < patch.row + patch.height; r++) {
            buffer.position(field.getOffset(r, patch.column) - field.getOffset());
            JSONArray row = new JSONArray();
            for (int c = 0; c < patch.width; c++) {
                double value = field.getType().readRawValue(buffer) * field.getMultiplier();
                row.add(Double.isFinite(value) ? value : null);
            }
            rows.add(row);
        }
        return rows;
    }

    private static void update(BinaryProtocol bp, IniFileModel ini, ArrayIniField field, JSONObject args,
                               byte[] current, byte[] expected, Patch patch, JSONObject result) {
        boolean burn = flag(args, "persist");
        try {
            for (int row = patch.row; row < patch.row + patch.height; row++) {
                int ecuOffset = field.getOffset(row, patch.column);
                int offset = ecuOffset - field.getOffset();
                int size = patch.width * field.getType().getStorageSize();
                if (!ByteBuffer.wrap(current, offset, size).equals(ByteBuffer.wrap(expected, offset, size))) {
                    bp.writeInBlocks(expected, offset, ecuOffset, size, field.getPageIndex());
                }
            }
            verify(bp, field, expected);
            if (burn) {
                burn(bp, ini, field);
                verify(bp, field, expected);
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("Map update could not be verified. ECU RAM may contain a partial update: "
                    + e.getMessage(), e);
        }
        result.put("verified", true);
        result.put("burnAcknowledged", burn);
        result.put("values", decode(ini, field, expected));
    }

    private static void burn(BinaryProtocol bp, IniFileModel ini, ArrayIniField field) {
        if (!bp.burnPage(field.getPageIndex())) {
            throw new IllegalStateException("ECU rejected the burn");
        }
        if (field.getPageIndex() == 0) {
            // A full settings-page CRC lets firmware finish the deferred burn immediately.
            bp.getCrcFromController(ini.getMetaInfo().getPageSize(0));
        }
    }

    private static void verify(BinaryProtocol bp, ArrayIniField field, byte[] expected) {
        if (!Arrays.equals(expected, read(bp, field))) {
            throw new IllegalStateException("Map readback differs from the expected values");
        }
    }

    private static final class Patch {
        final int row;
        final int column;
        final int height;
        final int width;

        Patch(int row, int column, int height, int width) {
            this.row = row;
            this.column = column;
            this.height = height;
            this.width = width;
        }
    }

    private static Patch applyPatch(IniFileModel ini, ArrayIniField field, JSONObject args, byte[] bytes) {
        int startRow = index(args.get("row"), field.getRows(), "row");
        int startColumn = index(args.get("column"), field.getCols(), "column");
        if (!(args.get("values") instanceof List)) {
            throw new IllegalArgumentException("values must be a rectangular matrix");
        }
        List<?> rows = (List<?>) args.get("values");
        if (rows.isEmpty() || rows.size() > field.getRows() - startRow) {
            throw new IllegalArgumentException("Patch exceeds the map rows or is empty");
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ini.getByteOrder());
        int width = 0;
        for (int r = 0; r < rows.size(); r++) {
            if (!(rows.get(r) instanceof List)) {
                throw new IllegalArgumentException("values must contain arrays of numbers");
            }
            List<?> row = (List<?>) rows.get(r);
            if (r == 0) {
                width = row.size();
            }
            if (width == 0 || width > field.getCols() - startColumn || row.size() != width) {
                throw new IllegalArgumentException("Patch must be rectangular and fit within the map columns");
            }
            buffer.position(field.getOffset(startRow + r, startColumn) - field.getOffset());
            for (Object value : row) {
                encode(field, value, buffer);
            }
        }
        return new Patch(startRow, startColumn, rows.size(), width);
    }

    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(pairs[i], pairs[i + 1]);
        }
        return result;
    }

    private static int index(Object value, int size, String name) {
        double number = value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        if (!(number >= 0 && number < size && number == Math.rint(number))) {
            throw new IllegalArgumentException(name + " must be a zero-based index within the map");
        }
        return (int) number;
    }

    private static void encode(ArrayIniField field, Object value, ByteBuffer buffer) {
        double number = value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        if (!Double.isFinite(number) || number < literalLimit(field.getMin(), Double.NEGATIVE_INFINITY)
                || number > literalLimit(field.getMax(), Double.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("Value must be finite and within the INI limits: " + value);
        }
        double raw = number / field.getMultiplier();
        FieldType type = field.getType();
        if (type == FieldType.FLOAT) {
            if (!Float.isFinite((float) raw)) {
                throw new IllegalArgumentException("Value exceeds float storage: " + value);
            }
            buffer.putFloat((float) raw);
        } else {
            int bits = type.getStorageSize() * Byte.SIZE;
            boolean unsigned = type == FieldType.UINT8 || type == FieldType.UINT16;
            long capacity = 1L << (unsigned ? bits : bits - 1);
            long rounded = Math.round(raw);
            if (rounded < (unsigned ? 0 : -capacity) || rounded >= capacity) {
                throw new IllegalArgumentException("Value exceeds integer storage: " + value);
            }
            type.writeRawValue(buffer, (int) rounded);
        }
    }

    private static double literalLimit(String limit, double fallback) {
        try {
            return limit == null ? fallback : Double.parseDouble(limit);
        } catch (NumberFormatException expression) {
            return fallback;
        }
    }
}
