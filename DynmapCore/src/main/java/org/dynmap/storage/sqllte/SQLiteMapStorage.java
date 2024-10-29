package org.dynmap.storage.sqllte;

import org.dynmap.*;
import org.dynmap.MapType.ImageVariant;
import org.dynmap.PlayerFaces.FaceType;
import org.dynmap.storage.*;
import org.dynmap.utils.BufferInputStream;
import org.dynmap.utils.BufferOutputStream;

import java.io.File;
import java.nio.charset.Charset;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class SQLiteMapStorage extends MapStorage {
    private String connectionString;
    private String databaseFile;
    private static final int POOLSIZE = 1;    // SQLite is really not thread safe... 1 at a time works best
    private Connection[] cpool = new Connection[POOLSIZE];
    private long[] cpoolLastUseTS = new long[POOLSIZE];    // Time when last returned to pool
    private static final long IDLE_TIMEOUT = 60000;    // Use 60 second timeout
    private int cpoolCount = 0;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public class StorageTile extends MapStorageTile {
        private Integer mapkey;
        private String uri;

        protected StorageTile(DynmapWorld world, MapType map, int x, int y,
                              int zoom, ImageVariant var) {
            super(world, map, x, y, zoom, var);

            mapkey = getMapKey(world, map, var);

            if (zoom > 0) {
                uri = map.getPrefix() + var.variantSuffix + "/" + (x >> 5) + "_" + (y >> 5) + "/" + "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz".substring(0, zoom) + "_" + x + "_" + y + "." + map.getImageFormat().getFileExt();
            } else {
                uri = map.getPrefix() + var.variantSuffix + "/" + (x >> 5) + "_" + (y >> 5) + "/" + x + "_" + y + "." + map.getImageFormat().getFileExt();
            }
        }

        @Override
        public boolean exists() {
            if (mapkey == null) return false;
            boolean rslt = false;
            Connection c = null;
            boolean err = false;
            try {
                c = getConnection();
                Statement stmt = c.createStatement();
                //ResultSet rs = stmt.executeQuery("SELECT HashCode FROM Tiles WHERE MapID=" + mapkey + " AND x=" + x + " AND y=" + y + " AND zoom=" + zoom + ";");
                ResultSet rs = doExecuteQuery(stmt, "SELECT HashCode FROM Tiles WHERE MapID=" + mapkey + " AND x=" + x + " AND y=" + y + " AND zoom=" + zoom + ";");
                rslt = rs.next();
                rs.close();
                stmt.close();
            } catch (SQLException x) {
                logSQLException("Tile exists error", x);
                err = true;
            } catch (StorageShutdownException x) {
                err = true;
            } finally {
                releaseConnection(c, err);
            }
            return rslt;
        }

        @Override
        public boolean matchesHashCode(long hash) {
            if (mapkey == null) return false;
            boolean rslt = false;
            Connection c = null;
            boolean err = false;
            try {
                c = getConnection();
                Statement stmt = c.createStatement();
                //ResultSet rs = stmt.executeQuery("SELECT HashCode FROM Tiles WHERE MapID=" + mapkey + " AND x=" + x + " AND y=" + y + " AND zoom=" + zoom + ";");
                ResultSet rs = doExecuteQuery(stmt, "SELECT HashCode FROM Tiles WHERE MapID=" + mapkey + " AND x=" + x + " AND y=" + y + " AND zoom=" + zoom + ";");
                if (rs.next()) {
                    long v = rs.getLong("HashCode");
                    rslt = (v == hash);
                }
                rs.close();
                stmt.close();
            } catch (SQLException x) {
                logSQLException("Tile matches hash error", x);
                err = true;
            } catch (StorageShutdownException x) {
                err = true;
            } finally {
                releaseConnection(c, err);
            }
            return rslt;
        }

        @Override
        public TileRead read() {
            if (mapkey == null) return null;
            TileRead rslt = null;
            Connection c = null;
            boolean err = false;
            try {
                c = getConnection();
                Statement stmt = c.createStatement();
                //ResultSet rs = stmt.executeQuery("SELECT HashCode,LastUpdate,Format,Image FROM Tiles WHERE MapID=" + mapkey + " AND x=" + x + " AND y=" + y + " AND zoom=" + zoom + ";");
                ResultSet rs = doExecuteQuery(stmt, "SELECT HashCode,LastUpdate,Format,Image,ImageLen FROM Tiles WHERE MapID=" + mapkey + " AND x=" + x + " AND y=" + y + " AND zoom=" + zoom + ";");
                if (rs.next()) {
                    rslt = new TileRead();
                    rslt.hashCode = rs.getLong("HashCode");
                    rslt.lastModified = rs.getLong("LastUpdate");
                    rslt.format = MapType.ImageEncoding.fromOrd(rs.getInt("Format"));
                    byte[] img = rs.getBytes("Image");
                    int len = rs.getInt("ImageLen");
                    if (len <= 0) {
                        len = img.length;
                        // Trim trailing zeros from padding by BLOB field
                        while ((len > 0) && (img[len - 1] == '\0')) len--;
                    }
                    if (img == null) {
                        rslt = null;
                    } else {
                        rslt.image = new BufferInputStream(img, len);
                    }
                }
                rs.close();
                stmt.close();
            } catch (SQLException x) {
                logSQLException("Tile read error", x);
                err = true;
            } catch (StorageShutdownException x) {
                err = true;
            } finally {
                releaseConnection(c, err);
            }
            return rslt;
        }

        @Override
        public boolean write(long hash, BufferOutputStream encImage, long timestamp) {
            if (mapkey == null) return false;
            Connection c = null;
            boolean err = false;
            boolean exists = exists();
            // If delete, and doesn't exist, quit
            if ((encImage == null) && (!exists)) return false;

            try {
                c = getConnection();
                PreparedStatement stmt;
                if (encImage == null) { // If delete
                    stmt = c.prepareStatement("DELETE FROM Tiles WHERE MapID=? AND x=? and y=? AND zoom=?;");
                    stmt.setInt(1, mapkey);
                    stmt.setInt(2, x);
                    stmt.setInt(3, y);
                    stmt.setInt(4, zoom);
                } else if (exists) {
                    stmt = c.prepareStatement("UPDATE Tiles SET HashCode=?, LastUpdate=?, Format=?, Image=?, ImageLen=? WHERE MapID=? AND x=? and y=? AND zoom=?;");
                    stmt.setLong(1, hash);
                    stmt.setLong(2, timestamp);
                    stmt.setInt(3, map.getImageFormat().getEncoding().ordinal());
                    stmt.setBytes(4, encImage.buf);
                    stmt.setInt(5, encImage.len);
                    stmt.setInt(6, mapkey);
                    stmt.setInt(7, x);
                    stmt.setInt(8, y);
                    stmt.setInt(9, zoom);
                } else {
                    stmt = c.prepareStatement("INSERT INTO Tiles (MapID,x,y,zoom,HashCode,LastUpdate,Format,Image,ImageLen) VALUES (?,?,?,?,?,?,?,?,?);");
                    stmt.setInt(1, mapkey);
                    stmt.setInt(2, x);
                    stmt.setInt(3, y);
                    stmt.setInt(4, zoom);
                    stmt.setLong(5, hash);
                    stmt.setLong(6, timestamp);
                    stmt.setInt(7, map.getImageFormat().getEncoding().ordinal());
                    stmt.setBytes(8, encImage.buf);
                    stmt.setInt(9, encImage.len);
                }
                //stmt.executeUpdate();
                doExecuteUpdate(stmt);
                stmt.close();
                // Signal update for zoom out
                if (zoom == 0) {
                    world.enqueueZoomOutUpdate(this);
                }
            } catch (SQLException x) {
                logSQLException("Tile write error", x);
                err = true;
            } catch (StorageShutdownException x) {
                err = true;
            } finally {
                releaseConnection(c, err);
            }
            return !err;
        }

        @Override
        public boolean getWriteLock() {
            return SQLiteMapStorage.this.getWriteLock(uri);
        }

        @Override
        public void releaseWriteLock() {
            SQLiteMapStorage.this.releaseWriteLock(uri);
        }

        @Override
        public boolean getReadLock(long timeout) {
            return SQLiteMapStorage.this.getReadLock(uri, timeout);
        }

        @Override
        public void releaseReadLock() {
            SQLiteMapStorage.this.releaseReadLock(uri);
        }

        @Override
        public void cleanup() {
        }

        @Override
        public String getURI() {
            return uri;
        }

        @Override
        public void enqueueZoomOutUpdate() {
            world.enqueueZoomOutUpdate(this);
        }

        @Override
        public MapStorageTile getZoomOutTile() {
            int xx, yy;
            int step = 1 << zoom;
            if (x >= 0)
                xx = x - (x % (2 * step));
            else
                xx = x + (x % (2 * step));
            yy = -y;
            if (yy >= 0)
                yy = yy - (yy % (2 * step));
            else
                yy = yy + (yy % (2 * step));
            yy = -yy;
            return new StorageTile(world, map, xx, yy, zoom + 1, var);
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof StorageTile) {
                StorageTile st = (StorageTile) o;
                return uri.equals(st.uri);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return uri.hashCode();
        }
    }

    public SQLiteMapStorage() {
    }

    @Override
    public boolean init(DynmapCore core) {
        if (!super.init(core)) {
            isShutdown = true;
            return false;
        }
        File dbfile = core.getFile(core.configuration.getString("storage/dbfile", "dynmap.db"));
        databaseFile = dbfile.getAbsolutePath();
        connectionString = "jdbc:sqlite:" + databaseFile;
        Log.info("Opening SQLite file " + databaseFile + " as map store");
        try {
            Class.forName("org.sqlite.JDBC");
            // Initialize/update tables, if needed
            return initializeTables();
        } catch (ClassNotFoundException cnfx) {
            Log.severe("SQLite-JDBC classes not found - sqlite data source not usable");
            isShutdown = true;
            return false;
        }
    }

    private int getSchemaVersion() {
        int ver = 0;
        boolean err = false;
        Connection c = null;
        try {
            c = getConnection();    // Get connection (create DB if needed)
            Statement stmt = c.createStatement();
            //ResultSet rs = stmt.executeQuery( "SELECT level FROM SchemaVersion;");
            ResultSet rs = doExecuteQuery(stmt, "SELECT level FROM SchemaVersion;");
            if (rs.next()) {
                ver = rs.getInt("level");
            }
            rs.close();
            stmt.close();
        } catch (SQLException x) {
            err = true;
        } catch (StorageShutdownException x) {
            err = true;
        } finally {
            if (c != null) {
                releaseConnection(c, err);
            }
        }
        return ver;
    }

    private void doUpdate(Connection c, String sql) throws SQLException {
        Statement stmt = c.createStatement();
        //stmt.executeUpdate(sql);
        doExecuteUpdate(stmt, sql);
        stmt.close();
    }

    private HashMap<String, Integer> mapKey = new HashMap<String, Integer>();

    private void doLoadMaps() {
        Connection c = null;
        boolean err = false;

        mapKey.clear();
        // Read the maps table - cache results
        try {
            c = getConnection();
            Statement stmt = c.createStatement();
            //ResultSet rs = stmt.executeQuery("SELECT * from Maps;");
            ResultSet rs = doExecuteQuery(stmt, "SELECT * from Maps;");
            while (rs.next()) {
                int key = rs.getInt("ID");
                String worldID = rs.getString("WorldID");
                String mapID = rs.getString("MapID");
                String variant = rs.getString("Variant");
                mapKey.put(worldID + ":" + mapID + ":" + variant, key);
            }
            rs.close();
            stmt.close();
        } catch (SQLException x) {
            logSQLException("Error loading map table", x);
            err = true;
        } catch (StorageShutdownException x) {
            err = true;
        } finally {
            releaseConnection(c, err);
            c = null;
        }
    }

    private Integer getMapKey(DynmapWorld w, MapType mt, ImageVariant var) {
        String id = w.getName() + ":" + mt.getPrefix() + ":" + var.toString();
        synchronized (mapKey) {
            Integer k = mapKey.get(id);
            if (k == null) {    // No hit: new value so we need to add it to table
                Connection c = null;
                boolean err = false;
                try {
                    c = getConnection();
                    // Insert row
                    PreparedStatement stmt = c.prepareStatement("INSERT INTO Maps (WorldID,MapID,Variant) VALUES (?, ?, ?);");
                    stmt.setString(1, w.getName());
                    stmt.setString(2, mt.getPrefix());
                    stmt.setString(3, var.toString());
                    //stmt.executeUpdate();
                    doExecuteUpdate(stmt);
                    stmt.close();
                    //  Query key assigned
                    stmt = c.prepareStatement("SELECT ID FROM Maps WHERE WorldID = ? AND MapID = ? AND Variant = ?;");
                    stmt.setString(1, w.getName());
                    stmt.setString(2, mt.getPrefix());
                    stmt.setString(3, var.toString());
                    //ResultSet rs = stmt.executeQuery();
                    ResultSet rs = doExecuteQuery(stmt);
                    if (rs.next()) {
                        k = rs.getInt("ID");
                        mapKey.put(id, k);
                    }
                    rs.close();
                    stmt.close();
                } catch (SQLException x) {
                    logSQLException("Error updating Maps table", x);
                    err = true;
                } catch (StorageShutdownException x) {
                    err = true;
                } finally {
                    releaseConnection(c, err);
                }
            }

            return k;
        }
    }

    private boolean initializeTables() {
        Connection c = null;
        boolean err = false;
        int version = getSchemaVersion();   // Get the existing schema version for the DB (if any)
        // If new, add our tables
        if (version == 0) {
            try {
                Log.info("Initializing database schema");
                c = getConnection();
                doUpdate(c, "CREATE TABLE Maps (ID INTEGER PRIMARY KEY AUTOINCREMENT, WorldID STRING NOT NULL, MapID STRING NOT NULL, Variant STRING NOT NULL)");
                doUpdate(c, "CREATE TABLE Tiles (MapID INT NOT NULL, x INT NOT NULL, y INT NOT NULL, zoom INT NOT NULL, HashCode INT NOT NULL, LastUpdate INT NOT NULL, Format INT NOT NULL, Image BLOB, ImageLen INT, PRIMARY KEY(MapID, x, y, zoom))");
                doUpdate(c, "CREATE TABLE Faces (PlayerName STRING NOT NULL, TypeID INT NOT NULL, Image BLOB, ImageLen INT, PRIMARY KEY(PlayerName, TypeID))");
                doUpdate(c, "CREATE TABLE MarkerIcons (IconName STRING PRIMARY KEY NOT NULL, Image BLOB, ImageLen INT)");
                doUpdate(c, "CREATE TABLE MarkerFiles (FileName STRING PRIMARY KEY NOT NULL, Content CLOB)");
                // Add index, since SQLite execution planner is stupid and scans Tiles table instead of using short Maps table...
                doUpdate(c, "CREATE INDEX MapIndex ON Maps(WorldID, MapID, Variant)");
                doUpdate(c, "CREATE TABLE SchemaVersion (level INT PRIMARY KEY NOT NULL)");
                doUpdate(c, "INSERT INTO SchemaVersion (level) VALUES (3)");
                version = 3;    // Initializes to current schema
            } catch (SQLException x) {
                logSQLException("Error creating tables", x);
                err = true;
                return false;
            } catch (StorageShutdownException x) {
                err = true;
                return false;
            } finally {
                releaseConnection(c, err);
                c = null;
            }
        }
        if (version == 1) {    // Add ImageLen columns
            try {
                Log.info("Updating database schema from version = " + version);
                c = getConnection();
                doUpdate(c, "ALTER TABLE Tiles ADD ImageLen INT");
                doUpdate(c, "ALTER TABLE Faces ADD ImageLen INT");
                doUpdate(c, "ALTER TABLE MarkerIcons ADD ImageLen INT");
                doUpdate(c, "UPDATE SchemaVersion SET level=2");
                version = 2;
            } catch (SQLException x) {
                logSQLException("Error updating tables to version=2", x);
                err = true;
                return false;
            } catch (StorageShutdownException x) {
                err = true;
                return false;
            } finally {
                releaseConnection(c, err);
                c = null;
            }
        }
        if (version == 2) {
            try {
                Log.info("Updating database schema from version = " + version);
                c = getConnection();
                // Add index, since SQLite execution planner is stupid and scans Tiles table instead of using short Maps table...
                doUpdate(c, "CREATE INDEX MapIndex ON Maps(WorldID, MapID, Variant)");
                doUpdate(c, "UPDATE SchemaVersion SET level=3");
                version = 2;
            } catch (SQLException x) {
                logSQLException("Error updating tables to version=3", x);
                err = true;
                return false;
            } catch (StorageShutdownException x) {
                err = true;
                return false;
            } finally {
                releaseConnection(c, err);
                c = null;
            }
        }
        Log.info("Schema version = " + version);
        // Load maps table - cache results
        doLoadMaps();

        return true;
    }

    private Connection getConnection() throws SQLException, StorageShutdownException {
        Connection c = null;
        if (isShutdown) {
            throw new StorageShutdownException();
        }
        synchronized (cpool) {
            long now = System.currentTimeMillis();
            while (c == null) {
                for (int i = 0; i < cpool.length; i++) {    // See if available connection
                    if (cpool[i] != null) { // Found one
                        // If in pool too long, close it and move on
                        if ((now - cpoolLastUseTS[i]) > IDLE_TIMEOUT) {
                            try {
                                cpool[i].close();
                            } catch (SQLException x) {
                            }
                            cpool[i] = null;
                            cpoolCount--;
                        } else {    // Else, use the connection
                            c = cpool[i];
                            cpool[i] = null;
                            cpoolLastUseTS[i] = now;
                            break;
                        }
                    }
                }
                if (c == null) {
                    if (cpoolCount < POOLSIZE) {  // Still more we can have
                        c = DriverManager.getConnection(connectionString);
                        configureConnection(c);
                        cpoolCount++;
                    } else {
                        try {
                            cpool.wait();
                        } catch (InterruptedException e) {
                            throw new SQLException("Interruped");
                        }
                    }
                }
            }
        }
        return c;
    }

    private static Connection configureConnection(Connection conn) throws SQLException {
        final Statement statement = conn.createStatement();
        statement.execute("PRAGMA auto_vacuum = FULL;");
        statement.execute("PRAGMA journal_mode = WAL;");
        statement.close();
        return conn;
    }

    private void releaseConnection(Connection c, boolean err) {
        if (c == null) return;
        synchronized (cpool) {
            if (!err) {  // Find slot to keep it in pool
                for (int i = 0; i < POOLSIZE; i++) {
                    if (cpool[i] == null) {
                        cpool[i] = c;
                        cpoolLastUseTS[i] = System.currentTimeMillis();    // Record last use time
                        c = null; // Mark it recovered (no close needed
                        cpool.notifyAll();
                        break;
                    }
                }
            }
            if (c != null) {  // If broken, just toss it
                try {
                    c.close();
                } catch (SQLException x) {
                }
                cpoolCount--;   // And reduce count
                cpool.notifyAll();
            }
        }
    }

    @Override
    public MapStorageTile getTile(DynmapWorld world, MapType map, int x, int y,
                                  int zoom, ImageVariant var) {
        return new StorageTile(world, map, x, y, zoom, var);
    }

    @Override
    public MapStorageTile getTile(DynmapWorld world, String uri) {
        String[] suri = uri.split("/");
        if (suri.length < 2) return null;
        String mname = suri[0]; // Map URI - might include variant
        MapType mt = null;
        ImageVariant imgvar = null;
        // Find matching map type and image variant
        for (int mti = 0; (mt == null) && (mti < world.maps.size()); mti++) {
            MapType type = world.maps.get(mti);
            ImageVariant[] var = type.getVariants();
            for (int ivi = 0; (imgvar == null) && (ivi < var.length); ivi++) {
                if (mname.equals(type.getPrefix() + var[ivi].variantSuffix)) {
                    mt = type;
                    imgvar = var[ivi];
                }
            }
        }
        if (mt == null) {   // Not found?
            return null;
        }
        // Now, take the last section and parse out coordinates and zoom
        String fname = suri[suri.length - 1];
        String[] coord = fname.split("[_\\.]");
        if (coord.length < 3) { // 3 or 4
            return null;
        }
        int zoom = 0;
        int x, y;
        try {
            if (coord[0].charAt(0) == 'z') {
                zoom = coord[0].length();
                x = Integer.parseInt(coord[1]);
                y = Integer.parseInt(coord[2]);
            } else {
                x = Integer.parseInt(coord[0]);
                y = Integer.parseInt(coord[1]);
            }
            return getTile(world, mt, x, y, zoom, imgvar);
        } catch (NumberFormatException nfx) {
            return null;
        }
    }

    @Override
    public void enumMapTiles(DynmapWorld world, MapType map,
                             MapStorageTileEnumCB cb) {
        List<MapType> mtlist;

        if (map != null) {
            mtlist = Collections.singletonList(map);
        } else {  // Else, add all directories under world directory (for maps)
            mtlist = new ArrayList<MapType>(world.maps);
        }
        for (MapType mt : mtlist) {
            ImageVariant[] vars = mt.getVariants();
            for (ImageVariant var : vars) {
                processEnumMapTiles(world, mt, var, cb, null, null);
            }
        }
    }

    @Override
    public void enumMapBaseTiles(DynmapWorld world, MapType map, MapStorageBaseTileEnumCB cbBase, MapStorageTileSearchEndCB cbEnd) {
        List<MapType> mtlist;

        if (map != null) {
            mtlist = Collections.singletonList(map);
        } else {  // Else, add all directories under world directory (for maps)
            mtlist = new ArrayList<MapType>(world.maps);
        }
        for (MapType mt : mtlist) {
            ImageVariant[] vars = mt.getVariants();
            for (ImageVariant var : vars) {
                processEnumMapTiles(world, mt, var, null, cbBase, cbEnd);
            }
        }
    }

    private void processEnumMapTiles(DynmapWorld world, MapType map, ImageVariant var, MapStorageTileEnumCB cb, MapStorageBaseTileEnumCB cbBase, MapStorageTileSearchEndCB cbEnd) {
        Connection c = null;
        boolean err = false;
        Integer mapkey = getMapKey(world, map, var);
        if (mapkey == null) {
            if (cbEnd != null)
                cbEnd.searchEnded();
            return;
        }
        try {
            boolean done = false;
            int offset = 0;
            int limit = 100;
            while (!done) {
                c = getConnection();    // Do inside loop - single threaded sqlite will have issues otherwise....
                // Query tiles for given mapkey
                Statement stmt = c.createStatement();
                ResultSet rs = doExecuteQuery(stmt, String.format("SELECT x,y,zoom,Format FROM Tiles WHERE MapID=%d LIMIT %d OFFSET %d;", mapkey, limit, offset));
                int cnt = 0;
                while (rs.next()) {
                    StorageTile st = new StorageTile(world, map, rs.getInt("x"), rs.getInt("y"), rs.getInt("zoom"), var);
                    final MapType.ImageEncoding encoding = MapType.ImageEncoding.fromOrd(rs.getInt("Format"));
                    if (cb != null)
                        cb.tileFound(st, encoding);
                    if (cbBase != null && st.zoom == 0)
                        cbBase.tileFound(st, encoding);
                    st.cleanup();
                    cnt++;
                }
                rs.close();
                stmt.close();
                if (cnt < limit) done = true;
                offset += cnt;
                releaseConnection(c, err);
                c = null;
            }
            if (cbEnd != null)
                cbEnd.searchEnded();
        } catch (SQLException x) {
            logSQLException("Tile enum error", x);
            err = true;
        } catch (StorageShutdownException x) {
            err = true;
        } finally {
            releaseConnection(c, err);
        }
    }

    @Override
    public void purgeMapTiles(DynmapWorld world, MapType map) {
        List<MapType> mtlist;

        if (map != null) {
            mtlist = Collections.singletonList(map);
        } else {  // Else, add all directories under world directory (for maps)
            mtlist = new ArrayList<MapType>(world.maps);
        }
        for (MapType mt : mtlist) {
            ImageVariant[] vars = mt.getVariants();
            for (ImageVariant var : vars) {
                processPurgeMapTiles(world, mt, var);
            }
        }
    }

    private void processPurgeMapTiles(DynmapWorld world, MapType map, ImageVariant var) {
        Connection c = null;
        boolean err = false;
        Integer mapkey = getMapKey(world, map, var);
        if (mapkey == null) return;
        try {
            c = getConnection();
            // Query tiles for given mapkey
            Statement stmt = c.createStatement();
            //stmt.executeUpdate("DELETE FROM Tiles WHERE MapID=" + mapkey + ";");
            doExecuteUpdate(stmt, "DELETE FROM Tiles WHERE MapID=" + mapkey + ";");
            stmt.close();
        } catch (SQLException x) {
            logSQLException("Tile purge error", x);
            err = true;
        } catch (StorageShutdownException x) {
            err = true;
        } finally {
            releaseConnection(c, err);
        }
    }

    @Override
    public boolean setPlayerFaceImage(String playername, FaceType facetype,
                                      BufferOutputStream encImage) {
        Connection c = null;
        boolean err = false;
        boolean exists = hasPlayerFaceImage(playername, facetype);
        // If delete, and doesn't exist, quit
        if ((encImage == null) && (!exists)) return false;

        try {
            c = getConnection();
            PreparedStatement stmt;
            if (encImage == null) { // If delete
                stmt = c.prepareStatement("DELETE FROM Faces WHERE PlayerName=? AND TypeIDx=?;");
                stmt.setString(1, playername);
                stmt.setInt(2, facetype.typeID);
            } else if (exists) {
                stmt = c.prepareStatement("UPDATE Faces SET Image=?,ImageLen=? WHERE PlayerName=? AND TypeID=?;");
                stmt.setBytes(1, encImage.buf);
                stmt.setInt(2, encImage.len);
                stmt.setString(3, playername);
                stmt.setInt(4, facetype.typeID);
            } else {
                stmt = c.prepareStatement("INSERT INTO Faces (PlayerName,TypeID,Image,ImageLen) VALUES (?,?,?,?);");
                stmt.setString(1, playername);
                stmt.setInt(2, facetype.typeID);
                stmt.setBytes(3, encImage.buf);
                stmt.setInt(4, encImage.len);
            }
            //stmt.executeUpdate();
            doExecuteUpdate(stmt);
            stmt.close();
        } catch (SQLException x) {
            logSQLException("Face write error", x);
            err = true;
        } catch (StorageShutdownException x) {
            err = true;
        } finally {
            releaseConnection(c, err);
        }
        return !err;
    }

    @Override
    public BufferInputStream getPlayerFaceImage(String playername,
                                                FaceType facetype) {
        Connection c = null;
        boolean err = false;
        BufferInputStream image = null;
        try {
            c = getConnection();
            PreparedStatement stmt = c.prepareStatement("SELECT Image,ImageLen FROM Faces WHERE PlayerName=? AND TypeID=?;");
            stmt.setString(1, playername);
            stmt.setInt(2, facetype.typeID);
            //ResultSet rs = stmt.executeQuery();
            ResultSet rs = doExecuteQuery(stmt);
            if (rs.next()) {
                byte[] img = rs.getBytes("Image");
                int len = rs.getInt("imageLen");
                if (len <= 0) {
                    len = img.length;
                    // Trim trailing zeros from padding by BLOB field
                    while ((len > 0) && (img[len - 1] == '\0')) len--;
                }
                image = new BufferInputStream(img, len);
            }
            rs.close();
            stmt.close();
        } catch (SQLException x) {
            logSQLException("Face read error", x);
            err = true;
        } catch (StorageShutdownException x) {
            err = true;
        } finally {
            releaseConnection(c, err);
        }
        return image;
    }

    @Override
    public boolean hasPlayerFaceImage(String playername, FaceType facetype) {
        Connection c = null;
        boolean err = false;
        boolean exists = false;
        try {
            c = getConnection();
            PreparedStatement stmt = c.prepareStatement("SELECT TypeID FROM Faces WHERE PlayerName=? AND TypeID=?;");
            stmt.setString(1, playername);
            stmt.setInt(2, facetype.typeID);
            //ResultSet rs = stmt.executeQuery();
            ResultSet rs = doExecuteQuery(stmt);
            if (rs.next()) {
                exists = true;
            }
            rs.close();
            stmt.close();
        } catch (SQLException x) {
            logSQLException("Face exists error", x);
            err = true;
        } catch (StorageShutdownException x) {
            err = true;
        } finally {
            releaseConnection(c, err);
        }
        return exists;
    }

    @Override
    public boolean setMarkerImage(String markerid, BufferOutputStream encImage) {
        Connection c = null;
        boolean err = false;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            c = getConnection();
            boolean exists = false;
            stmt = c.prepareStatement("SELECT IconName FROM MarkerIcons WHERE IconName=?;");
            stmt.setString(1, markerid);
            rs = doExecuteQuery(stmt);
            if (rs.next()) {
                exists = true;
            }
            rs.close();
            rs = null;
            stmt.close();
            stmt = null;
            if (encImage == null) { // If delete
                // If delete, and doesn't exist, quit
                if (!exists) return false;
                stmt = c.prepareStatement("DELETE FROM MarkerIcons WHERE IconName=?;");
                stmt.setString(1, markerid);
                //stmt.executeUpdate();
                doExecuteUpdate(stmt);
            } else if (exists) {
                stmt = c.prepareStatement("UPDATE MarkerIcons SET Image=?,ImageLen=? WHERE IconName=?;");
                stmt.setBytes(1, encImage.buf);
                stmt.setInt(2, encImage.len);
                stmt.setString(3, markerid);
            } else {
                stmt = c.prepareStatement("INSERT INTO MarkerIcons (IconName,Image,ImageLen) VALUES (?,?,?);");
                stmt.setString(1, markerid);
                stmt.setBytes(2, encImage.buf);
                stmt.setInt(3, encImage.len);
            }
            doExecuteUpdate(stmt);
            stmt.close();
            stmt = null;
        } catch (SQLException x) {
            logSQLException("Marker write error", x);
            err = true;
        } catch (StorageShutdownException x) {
            err = true;
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException sx) {
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException sx) {
                }
            }
            releaseConnection(c, err);
        }
        return !err;
    }

    @Override
    public BufferInputStream getMarkerImage(String markerid) {
        Connection c = null;
        boolean err = false;
        BufferInputStream image = null;
        try {
            c = getConnection();
            PreparedStatement stmt = c.prepareStatement("SELECT Image,ImageLen FROM MarkerIcons WHERE IconName=?;");
            stmt.setString(1, markerid);
            //ResultSet rs = stmt.executeQuery();
            ResultSet rs = doExecuteQuery(stmt);
            if (rs.next()) {
                byte[] img = rs.getBytes("Image");
                int len = rs.getInt("ImageLen");
                if (len <= 0) {
                    len = img.length;
                    // Trim trailing zeros from padding by BLOB field
                    while ((len > 0) && (img[len - 1] == '\0')) len--;
                }
                image = new BufferInputStream(img);
            }
            rs.close();
            stmt.close();
        } catch (SQLException x) {
            logSQLException("Marker read error", x);
            err = true;
        } catch (StorageShutdownException x) {
            err = true;
        } finally {
            releaseConnection(c, err);
        }
        return image;
    }

    @Override
    public boolean setMarkerFile(String world, String content) {
        Connection c = null;
        boolean err = false;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            c = getConnection();
            boolean exists = false;
            stmt = c.prepareStatement("SELECT FileName FROM MarkerFiles WHERE FileName=?;");
            stmt.setString(1, world);
            rs = doExecuteQuery(stmt);
            if (rs.next()) {
                exists = true;
            }
            rs.close();
            rs = null;
            stmt.close();
            stmt = null;
            if (content == null) { // If delete
                // If delete, and doesn't exist, quit
                if (!exists) return false;
                stmt = c.prepareStatement("DELETE FROM MarkerFiles WHERE FileName=?;");
                stmt.setString(1, world);
                doExecuteUpdate(stmt);
            } else if (exists) {
                stmt = c.prepareStatement("UPDATE MarkerFiles SET Content=? WHERE FileName=?;");
                stmt.setBytes(1, content.getBytes(UTF8));
                stmt.setString(2, world);
            } else {
                stmt = c.prepareStatement("INSERT INTO MarkerFiles (FileName,Content) VALUES (?,?);");
                stmt.setString(1, world);
                stmt.setBytes(2, content.getBytes(UTF8));
            }
            //stmt.executeUpdate();
            doExecuteUpdate(stmt);
        } catch (SQLException x) {
            logSQLException("Marker file write error", x);
            err = true;
        } catch (StorageShutdownException x) {
            err = true;
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException sx) {
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException sx) {
                }
            }
            releaseConnection(c, err);
        }
        return !err;
    }

    @Override
    public String getMarkerFile(String world) {
        Connection c = null;
        boolean err = false;
        String content = null;
        try {
            c = getConnection();
            PreparedStatement stmt = c.prepareStatement("SELECT Content FROM MarkerFiles WHERE FileName=?;");
            stmt.setString(1, world);
            //ResultSet rs = stmt.executeQuery();
            ResultSet rs = doExecuteQuery(stmt);
            if (rs.next()) {
                byte[] img = rs.getBytes("Content");
                content = new String(img, UTF8);
            }
            rs.close();
            stmt.close();
        } catch (SQLException x) {
            logSQLException("Marker file read error", x);
            err = true;
        } catch (StorageShutdownException x) {
            err = true;
        } finally {
            releaseConnection(c, err);
        }
        return content;
    }

    @Override
    // External web server only
    public String getMarkersURI(boolean login_enabled) {
        return "standalone/SQLite_markers.php?marker=";
    }

    @Override
    // External web server only
    public String getTilesURI(boolean login_enabled) {
        return "standalone/SQLite_tiles.php?tile=";
    }

    @Override
    public void addPaths(StringBuilder sb, DynmapCore core) {
        sb.append("$dbfile = \'");
        sb.append(WebAuthManager.esc(databaseFile));
        sb.append("\';\n");

        // Need to call base to add webpath
        super.addPaths(sb, core);
    }

    private ResultSet doExecuteQuery(PreparedStatement statement) throws SQLException {
        while (true) {
            try {
                return statement.executeQuery();
            } catch (SQLException x) {
                if (!x.getMessage().contains("[SQLITE_BUSY]")) {
                    throw x;
                }
            }
        }
    }

    private ResultSet doExecuteQuery(Statement statement, String sql) throws SQLException {
        while (true) {
            try {
                return statement.executeQuery(sql);
            } catch (SQLException x) {
                if (!x.getMessage().contains("[SQLITE_BUSY]")) {
                    throw x;
                }
            }
        }
    }

    private int doExecuteUpdate(PreparedStatement statement) throws SQLException {
        while (true) {
            try {
                return statement.executeUpdate();
            } catch (SQLException x) {
                if (!x.getMessage().contains("[SQLITE_BUSY]")) {
                    throw x;
                }
            }
        }
    }

    private int doExecuteUpdate(Statement statement, String sql) throws SQLException {
        while (true) {
            try {
                return statement.executeUpdate(sql);
            } catch (SQLException x) {
                if (!x.getMessage().contains("[SQLITE_BUSY]")) {
                    throw x;
                }
            }
        }
    }

    public void logSQLException(String opmsg, SQLException x) {
        // Ignore interrupted
        if (x.getMessage().equals("Interrupted")) return;

        super.logSQLException(opmsg, x);
    }
}
