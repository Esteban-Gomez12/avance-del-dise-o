package com.example.sqlite;

// Importa las clases necesarias para gestionar SQLite
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;

public class DBHelper extends SQLiteOpenHelper {

    //  Se MANTIENE la misma base de datos (mismo archivo). Solo sube la versión 1 -> 2.
    private static final String DB_NAME = "BD_EJEMPLO.db";
    private static final int DB_VERSION = 5;

    public static final String[] CATEGORIAS = {
            "Frutos secos", "Semillas", "Frutas deshidratadas", "Mezclas", "Otros"
    };

    public DBHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true); // Activa las llaves foráneas (USUARIO -> ROL)
    }

    //  Se ejecuta UNA SOLA VEZ al crear la base de datos por primera vez
    @Override
    public void onCreate(SQLiteDatabase db) {
        crearTablaPersona(db);
        crearTablasBodega(db);
        sembrarDatos(db);
        crearTablaCategoria(db);
        sembrarCategorias(db);
        crearTablaProveedor(db);
        insertarProveedoresDeEjemplo(db);
    }

    //  Al subir de versión NO se borra nada: la tabla "persona" y sus datos quedan intactos
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            crearTablasBodega(db);
            sembrarDatos(db);
        }
        // v3: módulo Categorías (RF-09 / RF-10).
        if (oldVersion < 3) {
            crearTablaCategoria(db);
            sembrarCategorias(db);
        }
        // v4: módulo Proveedores (soporte a compras / entradas).
        if (oldVersion < 4) {   // usa el número de versión que corresponda
            db.execSQL("DROP TABLE IF EXISTS PROVEEDOR");
            crearTablaProveedor(db);
            insertarProveedoresDeEjemplo(db);
        }
        if (oldVersion < 5) {
            repararProveedorSeguro(db);
        }
    }


    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!db.isReadOnly()) {
            repararProveedorSeguro(db);
        }
    }
    private void repararProveedorSeguro(SQLiteDatabase db) {
        try {
            repararTablaProveedor(db);
        } catch (Exception e) {
            try {
                db.execSQL("DROP TABLE IF EXISTS PROVEEDOR_OLD");
                db.execSQL("DROP TABLE IF EXISTS PROVEEDOR");
                crearTablaProveedor(db);
                insertarProveedoresDeEjemplo(db);
            } catch (Exception ignorado) {
                android.util.Log.e("DBHelper", "No se pudo reparar PROVEEDOR", ignorado);
            }
        }
    }
    private void repararTablaProveedor(SQLiteDatabase db) {
        Set<String> cols = new HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(PROVEEDOR)", null)) {
            while (c.moveToNext()) cols.add(c.getString(1).toLowerCase(Locale.ROOT));
        }
        if (cols.isEmpty()) {            // la tabla no existe
            crearTablaProveedor(db);
            insertarProveedoresDeEjemplo(db);
            return;
        }
        if (cols.contains("id_proveedor") && cols.contains("rut_empresa")
                && cols.contains("razon_social") && cols.contains("numero_contacto")
                && cols.contains("direccion")) {
            return;                      // esquema correcto, nada que hacer
        }

        // Esquema distinto: se crea la tabla nueva, se copia lo que coincida y se reemplaza.
        String rut = cols.contains("rut_empresa") ? "rut_empresa" : cols.contains("rut") ? "rut" : null;
        String razon = cols.contains("razon_social") ? "razon_social"
                : cols.contains("nombre") ? "nombre" : null;
        String tel = cols.contains("numero_contacto") ? "numero_contacto"
                : cols.contains("telefono") ? "telefono"
                : cols.contains("contacto") ? "contacto" : null;
        String dir = cols.contains("direccion") ? "direccion" : null;

        db.execSQL("DROP TABLE IF EXISTS PROVEEDOR_OLD");
        db.execSQL("ALTER TABLE PROVEEDOR RENAME TO PROVEEDOR_OLD");
        crearTablaProveedor(db);
        if (rut != null && razon != null) {
            db.execSQL("INSERT OR IGNORE INTO PROVEEDOR (rut_empresa, razon_social, numero_contacto, direccion) " +
                    "SELECT " + rut + ", " + razon + ", " +
                    (tel != null ? "COALESCE(" + tel + ", '')" : "''") + ", " +
                    (dir != null ? dir : "NULL") + " FROM PROVEEDOR_OLD");
        }
        db.execSQL("DROP TABLE PROVEEDOR_OLD");
        insertarProveedoresDeEjemplo(db);   // INSERT con CONFLICT_IGNORE: no duplica
    }

    // ---------------------------------------------------------------- Esquema

    // Tabla original del proyecto (se conserva tal cual)
    private void crearTablaPersona(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS persona (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "nombre TEXT NOT NULL, " +
                "apellido TEXT NOT NULL, " +
                "edad INTEGER NOT NULL)");
    }

    private void crearTablasBodega(SQLiteDatabase db) {
        // RF-27: nombre_rol único (UK)
        db.execSQL("CREATE TABLE IF NOT EXISTS ROL (" +
                "id_rol INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "nombre_rol TEXT NOT NULL UNIQUE, " +
                "descripcion TEXT)");

        // RF-01 / RF-27: username único (UK), password_hash, activo (1 = activo)
        db.execSQL("CREATE TABLE IF NOT EXISTS USUARIO (" +
                "id_usuario INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "username TEXT NOT NULL UNIQUE, " +
                "password_hash TEXT NOT NULL, " +
                "nombre_completo TEXT NOT NULL, " +
                "id_rol INTEGER NOT NULL, " +
                "activo INTEGER NOT NULL DEFAULT 1, " +
                "FOREIGN KEY (id_rol) REFERENCES ROL(id_rol))");

        // Inventario de la bodega (stock en kg, precio CLP por kg)
        db.execSQL("CREATE TABLE IF NOT EXISTS PRODUCTO (" +
                "id_producto INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "nombre TEXT NOT NULL UNIQUE, " +
                "categoria TEXT NOT NULL, " +
                "stock REAL NOT NULL DEFAULT 0, " +
                "stock_minimo REAL NOT NULL DEFAULT 0, " +
                "precio_kg INTEGER NOT NULL DEFAULT 0)");
    }

    // RF-09: nombre_categoria obligatorio y único (UK, sin distinguir mayúsculas), activo = 1 por defecto
    private void crearTablaCategoria(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS CATEGORIA (" +
                "id_categoria INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "nombre_categoria TEXT NOT NULL UNIQUE COLLATE NOCASE, " +
                "descripcion TEXT, " +
                "activo INTEGER NOT NULL DEFAULT 1)");
    }

    // Proveedores: rut_empresa único (UK); razón social y número de contacto obligatorios
    private void crearTablaProveedor(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS PROVEEDOR (" +
                "id_proveedor INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "rut_empresa TEXT NOT NULL UNIQUE, " +
                "razon_social TEXT NOT NULL, " +
                "numero_contacto TEXT NOT NULL, " +
                "direccion TEXT)");
    }

    // Carga las categorías que ya usaba el inventario para que el catálogo no parta vacío
    private void sembrarCategorias(SQLiteDatabase db) {
        for (String nombre : CATEGORIAS) {
            db.execSQL("INSERT OR IGNORE INTO CATEGORIA (nombre_categoria, activo) VALUES (?, 1)",
                    new Object[]{nombre});
        }
    }

    // Datos iniciales: 2 roles, 1 usuario administrador y algunos productos de ejemplo
    private void sembrarDatos(SQLiteDatabase db) {
        db.execSQL("INSERT OR IGNORE INTO ROL (nombre_rol, descripcion) VALUES (?, ?)",
                new Object[]{"ADMIN", "Administra usuarios, roles e inventario"});
        db.execSQL("INSERT OR IGNORE INTO ROL (nombre_rol, descripcion) VALUES (?, ?)",
                new Object[]{"BODEGUERO", "Gestiona el inventario de la bodega"});

        // Usuario inicial: admin / admin123  (cámbiela después de entrar)
        db.execSQL("INSERT OR IGNORE INTO USUARIO (username, password_hash, nombre_completo, id_rol, activo) " +
                        "SELECT ?, ?, ?, id_rol, 1 FROM ROL WHERE nombre_rol = 'ADMIN'",
                new Object[]{"admin", hash("admin", "admin123"), "Administrador"});

        Object[][] productos = {
                {"Almendras", "Frutos secos", 25.0, 5.0, 14500},
                {"Nueces mariposa", "Frutos secos", 18.5, 5.0, 12900},
                {"Maní salado", "Frutos secos", 3.0, 8.0, 5200},
                {"Pistachos", "Frutos secos", 9.0, 3.0, 19800},
                {"Castañas de cajú", "Frutos secos", 12.0, 4.0, 17500},
                {"Avellanas", "Frutos secos", 7.5, 3.0, 15900},
                {"Pasas rubias", "Frutas deshidratadas", 15.0, 5.0, 4900},
                {"Semillas de zapallo", "Semillas", 6.0, 2.0, 8900},
                {"Mix tropical", "Mezclas", 10.0, 4.0, 9900},
        };
        for (Object[] p : productos) {
            db.execSQL("INSERT OR IGNORE INTO PRODUCTO (nombre, categoria, stock, stock_minimo, precio_kg) " +
                    "VALUES (?, ?, ?, ?, ?)", p);
        }
    }
    private void insertarProveedoresDeEjemplo(SQLiteDatabase db) {
        String[][] datos = {
                {"76123456-7", "Frutos Secos San Marcos", "+56 9 1234 5678", "Santiago Centro, Santiago"},
                {"77888999-K", "Lácteos del Sur Ltda.", "+56 2 2345 6789", "Maipú, Santiago"},
                {"96555444-3", "Abarrotes Don Pedro S.A.", "+56 9 8765 4321", "Providencia, Santiago"}
        };
        for (String[] d : datos) {
            ContentValues v = new ContentValues();
            v.put("rut_empresa", d[0]);
            v.put("razon_social", d[1]);
            v.put("numero_contacto", d[2]);
            v.put("direccion", d[3]);
            db.insertWithOnConflict("PROVEEDOR", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }


    // ---------------------------------------------------------------- Seguridad

    // Hash SHA-256 de (username en minúsculas + ":" + contraseña). Nunca se guarda la clave en texto plano.
    public static String hash(String username, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((username.trim().toLowerCase(Locale.ROOT) + ":" + password)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // RF-01: solo ingresan usuarios activos (activo = 1) con credenciales correctas.
    // Devuelve el usuario con su rol, o null si no coincide.
    public Usuario autenticar(String username, String password) {
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT u.id_usuario, u.username, u.nombre_completo, u.id_rol, r.nombre_rol, u.activo " +
                "FROM USUARIO u JOIN ROL r ON r.id_rol = u.id_rol " +
                "WHERE u.username = ? AND u.password_hash = ? AND u.activo = 1";
        try (Cursor c = db.rawQuery(sql, new String[]{username.trim(), hash(username, password)})) {
            return c.moveToFirst() ? leerUsuario(c) : null;
        }
    }

    // Para detectar si una sesión guardada pertenece a un usuario que fue desactivado o eliminado
    public boolean usuarioActivo(long idUsuario) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT 1 FROM USUARIO WHERE id_usuario = ? AND activo = 1",
                new String[]{String.valueOf(idUsuario)})) {
            return c.moveToFirst();
        }
    }

    private Usuario leerUsuario(Cursor c) {
        Usuario u = new Usuario();
        u.idUsuario = c.getLong(0);
        u.username = c.getString(1);
        u.nombreCompleto = c.getString(2);
        u.idRol = c.getLong(3);
        u.nombreRol = c.getString(4);
        u.activo = c.getInt(5) == 1;
        return u;
    }

    // ---------------------------------------------------------------- Roles (RF-27)

    public List<Rol> listarRoles() {
        List<Rol> lista = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT id_rol, nombre_rol, descripcion FROM ROL ORDER BY nombre_rol", null)) {
            while (c.moveToNext()) {
                Rol r = new Rol();
                r.idRol = c.getLong(0);
                r.nombreRol = c.getString(1);
                r.descripcion = c.getString(2);
                lista.add(r);
            }
        }
        return lista;
    }

    // Lanza SQLiteConstraintException si el nombre del rol ya existe (UK)
    public void insertarRol(String nombreRol, String descripcion) throws SQLiteConstraintException {
        ContentValues v = new ContentValues();
        v.put("nombre_rol", nombreRol);
        v.put("descripcion", descripcion);
        getWritableDatabase().insertOrThrow("ROL", null, v);
    }

    public void actualizarRol(long idRol, String nombreRol, String descripcion) throws SQLiteConstraintException {
        ContentValues v = new ContentValues();
        v.put("nombre_rol", nombreRol);
        v.put("descripcion", descripcion);
        getWritableDatabase().updateWithOnConflict("ROL", v, "id_rol = ?",
                new String[]{String.valueOf(idRol)}, SQLiteDatabase.CONFLICT_ABORT);
    }

    // ---------------------------------------------------------------- Usuarios (RF-27)

    public List<Usuario> listarUsuarios() {
        List<Usuario> lista = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT u.id_usuario, u.username, u.nombre_completo, u.id_rol, r.nombre_rol, u.activo " +
                "FROM USUARIO u JOIN ROL r ON r.id_rol = u.id_rol ORDER BY u.username";
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) lista.add(leerUsuario(c));
        }
        return lista;
    }

    // Lanza SQLiteConstraintException si el username ya existe (UK)
    public void insertarUsuario(String username, String password, String nombreCompleto,
                                long idRol, boolean activo) throws SQLiteConstraintException {
        ContentValues v = new ContentValues();
        v.put("username", username);
        v.put("password_hash", hash(username, password));
        v.put("nombre_completo", nombreCompleto);
        v.put("id_rol", idRol);
        v.put("activo", activo ? 1 : 0);
        getWritableDatabase().insertOrThrow("USUARIO", null, v);
    }

    // nuevaPassword puede ser null/vacía para dejar la contraseña actual
    public void actualizarUsuario(long idUsuario, String username, String nombreCompleto,
                                  long idRol, boolean activo, String nuevaPassword) {
        ContentValues v = new ContentValues();
        v.put("nombre_completo", nombreCompleto);
        v.put("id_rol", idRol);
        v.put("activo", activo ? 1 : 0);
        if (nuevaPassword != null && !nuevaPassword.isEmpty()) {
            v.put("password_hash", hash(username, nuevaPassword));
        }
        getWritableDatabase().update("USUARIO", v, "id_usuario = ?", new String[]{String.valueOf(idUsuario)});
    }

    // ---------------------------------------------------------------- Categorías (RF-09 / RF-10)

    // soloActivas = true -> solo las habilitadas (para asignarlas a productos, RF-10)
    public List<Categoria> listarCategorias(boolean soloActivas) {
        List<Categoria> lista = new ArrayList<>();
        String sql = "SELECT id_categoria, nombre_categoria, descripcion, activo FROM CATEGORIA" +
                (soloActivas ? " WHERE activo = 1" : "") + " ORDER BY nombre_categoria";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                Categoria cat = new Categoria();
                cat.idCategoria = c.getLong(0);
                cat.nombreCategoria = c.getString(1);
                cat.descripcion = c.getString(2);
                cat.activo = c.getInt(3) == 1;
                lista.add(cat);
            }
        }
        return lista;
    }

    // Solo los nombres de categorías activas, para el desplegable de productos
    public List<String> listarNombresCategoriasActivas() {
        List<String> nombres = new ArrayList<>();
        for (Categoria c : listarCategorias(true)) nombres.add(c.nombreCategoria);
        return nombres;
    }

    // Lanza SQLiteConstraintException si el nombre ya existe (UK). activo = 1 por defecto.
    public void insertarCategoria(String nombreCategoria, String descripcion) throws SQLiteConstraintException {
        ContentValues v = new ContentValues();
        v.put("nombre_categoria", nombreCategoria);
        v.put("descripcion", descripcion);
        v.put("activo", 1);
        getWritableDatabase().insertOrThrow("CATEGORIA", null, v);
    }
    public void actualizarCategoria(long idCategoria, String nombreCategoria, String descripcion)
            throws SQLiteConstraintException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String nombreAnterior = null;
            try (Cursor c = db.rawQuery("SELECT nombre_categoria FROM CATEGORIA WHERE id_categoria = ?",
                    new String[]{String.valueOf(idCategoria)})) {
                if (c.moveToFirst()) nombreAnterior = c.getString(0);
            }
            ContentValues v = new ContentValues();
            v.put("nombre_categoria", nombreCategoria);
            v.put("descripcion", descripcion);
            db.updateWithOnConflict("CATEGORIA", v, "id_categoria = ?",
                    new String[]{String.valueOf(idCategoria)}, SQLiteDatabase.CONFLICT_ABORT);
            if (nombreAnterior != null && !nombreAnterior.equals(nombreCategoria)) {
                ContentValues pv = new ContentValues();
                pv.put("categoria", nombreCategoria);
                db.update("PRODUCTO", pv, "categoria = ? COLLATE NOCASE", new String[]{nombreAnterior});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---------------------------------------------------------------- Proveedores

    // También sirve para el selector de proveedor al registrar movimientos de tipo entrada
    public List<Proveedor> listarProveedores() {
        List<Proveedor> lista = new ArrayList<>();
        String sql = "SELECT id_proveedor, rut_empresa, razon_social, numero_contacto, direccion " +
                "FROM PROVEEDOR ORDER BY razon_social";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                Proveedor p = new Proveedor();
                p.idProveedor = c.getLong(0);
                p.rutEmpresa = c.getString(1);
                p.razonSocial = c.getString(2);
                p.numeroContacto = c.getString(3);
                p.direccion = c.getString(4);
                lista.add(p);
            }
        }
        return lista;
    }

    // Lanza SQLiteConstraintException si el rut_empresa ya existe (UK)
    public void insertarProveedor(String rutEmpresa, String razonSocial, String numeroContacto,
                                  String direccion) throws SQLiteConstraintException {
        ContentValues v = new ContentValues();
        v.put("rut_empresa", rutEmpresa);
        v.put("razon_social", razonSocial);
        v.put("numero_contacto", numeroContacto);
        v.put("direccion", (direccion == null || direccion.isEmpty()) ? null : direccion);
        getWritableDatabase().insertOrThrow("PROVEEDOR", null, v);
    }
    public boolean actualizarProveedor(long idProveedor, String rutEmpresa, String razonSocial,
                                       String numeroContacto, String direccion)
            throws SQLiteConstraintException {
        ContentValues v = new ContentValues();
        v.put("rut_empresa", rutEmpresa);
        v.put("razon_social", razonSocial);
        v.put("numero_contacto", numeroContacto);
        v.put("direccion", (direccion == null || direccion.isEmpty()) ? null : direccion);
        return getWritableDatabase().updateWithOnConflict("PROVEEDOR", v, "id_proveedor = ?",
                new String[]{String.valueOf(idProveedor)}, SQLiteDatabase.CONFLICT_ABORT) > 0;
    }

    // ---------------------------------------------------------------- Productos

    public List<Producto> listarProductos(String filtro) {
        List<Producto> lista = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT id_producto, nombre, categoria, stock, stock_minimo, precio_kg FROM PRODUCTO";
        String[] args = null;
        if (filtro != null && !filtro.trim().isEmpty()) {
            sql += " WHERE nombre LIKE ? OR categoria LIKE ?";
            String like = "%" + filtro.trim() + "%";
            args = new String[]{like, like};
        }
        sql += " ORDER BY nombre";
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext()) lista.add(leerProducto(c));
        }
        return lista;
    }

    public Producto obtenerProducto(long id) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT id_producto, nombre, categoria, stock, stock_minimo, precio_kg " +
                "FROM PRODUCTO WHERE id_producto = ?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? leerProducto(c) : null;
        }
    }

    private Producto leerProducto(Cursor c) {
        Producto p = new Producto();
        p.idProducto = c.getLong(0);
        p.nombre = c.getString(1);
        p.categoria = c.getString(2);
        p.stock = c.getDouble(3);
        p.stockMinimo = c.getDouble(4);
        p.precioKg = c.getInt(5);
        return p;
    }

    public int contarProductos() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM PRODUCTO", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int contarStockBajo() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM PRODUCTO WHERE stock <= stock_minimo", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    // Lanza SQLiteConstraintException si el nombre del producto ya existe
    public void insertarProducto(Producto p) throws SQLiteConstraintException {
        getWritableDatabase().insertOrThrow("PRODUCTO", null, valores(p));
    }

    public void actualizarProducto(Producto p) throws SQLiteConstraintException {
        getWritableDatabase().updateWithOnConflict("PRODUCTO", valores(p), "id_producto = ?",
                new String[]{String.valueOf(p.idProducto)}, SQLiteDatabase.CONFLICT_ABORT);
    }

    public boolean eliminarProducto(long id) {
        return getWritableDatabase().delete("PRODUCTO", "id_producto = ?",
                new String[]{String.valueOf(id)}) > 0;
    }

    private ContentValues valores(Producto p) {
        ContentValues v = new ContentValues();
        v.put("nombre", p.nombre);
        v.put("categoria", p.categoria);
        v.put("stock", p.stock);
        v.put("stock_minimo", p.stockMinimo);
        v.put("precio_kg", p.precioKg);
        return v;
    }
}
