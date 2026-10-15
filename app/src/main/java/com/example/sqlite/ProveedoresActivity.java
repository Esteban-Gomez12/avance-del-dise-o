package com.example.sqlite;

import android.database.sqlite.SQLiteConstraintException;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

// Gestión de Proveedores (soporte a compras / entradas): registrar y listar.
// Accesible para ADMIN y BODEGUERO (cualquier sesión iniciada).
public class ProveedoresActivity extends ProtegidaActivity {

    private ArrayAdapter<String> adapter;
    private final List<String> textos = new ArrayList<>();
    private final List<Proveedor> proveedores = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_proveedores);

        ListView lista = findViewById(R.id.lstProveedores);
        MaterialButton btnNuevo = findViewById(R.id.btnNuevoProveedor);
        MaterialButton btnVolver = findViewById(R.id.btnVolver);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, textos);
        lista.setAdapter(adapter);
        lista.setOnItemClickListener((parent,view,pos,id)->mostrarDetalle(proveedores.get(pos)));

        btnNuevo.setOnClickListener(v -> dialogoNuevoProveedor());
        btnVolver.setOnClickListener(v -> finish());

        cargar();
    }

    private void cargar() {
        textos.clear();
        proveedores.clear();
        DBHelper helper = new DBHelper(this);
        try {
            for (Proveedor p : helper.listarProveedores()) {
                proveedores.add(p);
                textos.add(p.razonSocial + "\nRUT: " + p.rutEmpresa + "  •  Tel: " + p.numeroContacto);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error al leer: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            helper.close();
        }
        adapter.notifyDataSetChanged();
    }

    private void dialogoNuevoProveedor() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_proveedor, null);
        TextInputEditText txtRut = v.findViewById(R.id.dlgRutEmpresa);
        TextInputEditText txtRazon = v.findViewById(R.id.dlgRazonSocial);
        TextInputEditText txtContacto = v.findViewById(R.id.dlgNumeroContacto);
        TextInputEditText txtDireccion = v.findViewById(R.id.dlgDireccion);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Nuevo proveedor")
                .setView(v)
                .setPositiveButton("Registrar", null)   // se sobreescribe abajo
                .setNegativeButton("Cancelar", null)
                .create();

        // Se sobreescribe el click para NO cerrar el diálogo si hay errores de validación
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String rutIngresado = txtRut.getText().toString().trim();
            String razon = txtRazon.getText().toString().trim();
            String contacto = txtContacto.getText().toString().trim();
            String direccion = txtDireccion.getText().toString().trim();

            boolean ok = true;
            String rut = Proveedor.normalizarRut(rutIngresado);
            if (rutIngresado.isEmpty()) {
                txtRut.setError("El RUT de la empresa es obligatorio");
                ok = false;
            } else if (rut == null) {
                txtRut.setError("RUT inválido (ej: 76123456-7)");
                ok = false;
            }
            if (razon.isEmpty()) {
                txtRazon.setError("La razón social es obligatoria");
                ok = false;
            }
            if (contacto.isEmpty()) {
                txtContacto.setError("El número de contacto es obligatorio");
                ok = false;
            } else if (!Proveedor.telefonoValido(contacto)) {
                txtContacto.setError("Número inválido (mínimo 8 dígitos)");
                ok = false;
            }
            if (!ok) return;

            DBHelper helper = new DBHelper(this);
            try {
                helper.insertarProveedor(rut, razon, contacto, direccion);
                Toast.makeText(this, "Proveedor registrado correctamente.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                cargar();
            } catch (SQLiteConstraintException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("UNIQUE")) {
                    txtRut.setError("Ya existe un proveedor con ese RUT");   // rut_empresa duplicado (UK)
                } else {
                    Toast.makeText(this, "Error al guardar: " + msg, Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Error al guardar: " + e.getMessage(), Toast.LENGTH_LONG).show();
            } finally {
                helper.close();
            }
        }));
        dialog.show();
    }
    private void mostrarDetalle(Proveedor p) {
        TextInputEditText cantidad = new TextInputEditText(this);
        cantidad.setHint("Cantidad a pedir");
        cantidad.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        android.widget.FrameLayout contenedor = new android.widget.FrameLayout(this);
        int m = (int) (24 * getResources().getDisplayMetrics().density);
        contenedor.setPadding(m, 8, m, 0);
        contenedor.addView(cantidad);

        new MaterialAlertDialogBuilder(this)
                .setTitle(p.razonSocial)
                .setMessage("RUT: " + p.rutEmpresa +
                        "\nTeléfono: " + p.numeroContacto +
                        "\nDirección: " + (p.direccion == null || p.direccion.isEmpty() ? "—" : p.direccion))
                .setView(contenedor)
                .setPositiveButton("Hacer pedido", (d, w) -> {
                    String c = cantidad.getText().toString().trim();
                    if (c.isEmpty()) {
                        Toast.makeText(this, "Ingresa una cantidad", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Pedido de " + c + " unidades a " + p.razonSocial,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }
}
