package com.samuel.pantallalimpia;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final int REQ_IMAGEN = 1;
    private static final int REQ_NOTIF = 2;

    private TextView estado;
    private ImageView vista;
    private Button btnPermiso;
    private SharedPreferences prefs;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button boton(String texto, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(texto);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        raiz.setPadding(p, p, p, p);

        TextView titulo = new TextView(this);
        titulo.setText("Pantalla limpia");
        titulo.setTextSize(24);
        raiz.addView(titulo);

        TextView ayuda = new TextView(this);
        ayuda.setText("Muestra tu imagen a pantalla completa encima de cualquier app (por ejemplo JW Library) con un toque, sin cambiar de aplicación.");
        ayuda.setPadding(0, dp(8), 0, dp(8));
        raiz.addView(ayuda);

        vista = new ImageView(this);
        vista.setScaleType(ImageView.ScaleType.FIT_CENTER);
        vista.setBackgroundColor(0x22888888);
        raiz.addView(vista, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)));

        raiz.addView(boton("1. Elegir imagen", v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "Elegir imagen"), REQ_IMAGEN);
        }));

        CheckBox rellenar = new CheckBox(this);
        rellenar.setText("Rellenar toda la pantalla (recorta los bordes)");
        rellenar.setChecked(prefs.getBoolean("fill", false));
        rellenar.setOnCheckedChangeListener((cb, marcado) ->
                prefs.edit().putBoolean("fill", marcado).apply());
        raiz.addView(rellenar);

        btnPermiso = boton("2. Permitir mostrar sobre otras apps", v -> pedirPermiso());
        raiz.addView(btnPermiso);

        raiz.addView(boton("3. Activar botón flotante", v -> activar()));

        raiz.addView(boton("Detener", v -> {
            stopService(new Intent(this, OverlayService.class));
            estado.postDelayed(this::actualizar, 400);
        }));

        estado = new TextView(this);
        estado.setPadding(0, dp(16), 0, 0);
        raiz.addView(estado);

        ScrollView sv = new ScrollView(this);
        sv.addView(raiz);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        actualizar();
    }

    private void pedirPermiso() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void activar() {
        File f = new File(getFilesDir(), "fondo.img");
        if (!f.exists()) {
            Toast.makeText(this, "Primero elige una imagen", Toast.LENGTH_LONG).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Falta el permiso para mostrar sobre otras apps", Toast.LENGTH_LONG).show();
            pedirPermiso();
            return;
        }
        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_START);
        startForegroundService(i);
        Toast.makeText(this, "Listo: ya puedes abrir JW Library y buscar el botón flotante", Toast.LENGTH_LONG).show();
        estado.postDelayed(this::actualizar, 600);
    }

    private void actualizar() {
        File f = new File(getFilesDir(), "fondo.img");
        Bitmap bm = f.exists() ? OverlayService.cargar(f, 800) : null;
        vista.setImageBitmap(bm);
        boolean permiso = Settings.canDrawOverlays(this);
        estado.setText("Imagen: " + (bm != null ? "lista" : "sin elegir")
                + "\nPermiso para mostrar sobre otras apps: " + (permiso ? "sí" : "falta")
                + "\nBotón flotante: " + (OverlayService.running ? "activo" : "apagado"));
        btnPermiso.setVisibility(permiso ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMAGEN && res == RESULT_OK && data != null && data.getData() != null) {
            try (InputStream in = getContentResolver().openInputStream(data.getData());
                 OutputStream out = new FileOutputStream(new File(getFilesDir(), "fondo.img"))) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } catch (Exception e) {
                Toast.makeText(this, "No pude guardar la imagen", Toast.LENGTH_LONG).show();
                return;
            }
            if (OverlayService.running) {
                startService(new Intent(this, OverlayService.class)
                        .setAction(OverlayService.ACTION_RELOAD));
            }
        }
        actualizar();
    }
}
