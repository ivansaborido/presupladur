package com.ivansaborido.presupladur;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * PresuPladur: la app web va dentro del APK (assets/index.html) y esta actividad
 * le da lo que un WebView no trae de serie: cámara, galería, dictado por voz
 * y enviar o abrir archivos (PDF, copia de seguridad).
 */
public class MainActivity extends Activity {
    private static final int RC_ARCHIVO = 1;
    private static final int RC_VOZ = 2;
    private static final String INICIO = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private ValueCallback<Uri[]> subida;
    private Uri fotoUri;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        getWindow().setStatusBarColor(0xFF0B5CB8);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);

        final WebViewAssetLoader cargador = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return cargador.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception e) {
                    aviso("No se ha podido abrir el enlace.");
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (subida != null) subida.onReceiveValue(null);
                subida = cb;
                fotoUri = null;
                try {
                    Intent i;
                    if (p.isCaptureEnabled()) {
                        File f = new File(carpeta(), "foto_" + System.currentTimeMillis() + ".jpg");
                        fotoUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".archivos", f);
                        i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        i.putExtra(MediaStore.EXTRA_OUTPUT, fotoUri);
                        i.setClipData(ClipData.newRawUri("foto", fotoUri));
                        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else {
                        String[] tipos = p.getAcceptTypes();
                        boolean imagen = tipos != null && tipos.length > 0 && tipos[0] != null && tipos[0].startsWith("image");
                        i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType(imagen ? "image/*" : "*/*");
                        if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        }
                    }
                    startActivityForResult(i, RC_ARCHIVO);
                } catch (Exception e) {
                    subida.onReceiveValue(null);
                    subida = null;
                    fotoUri = null;
                    aviso("No se ha podido abrir la cámara o la galería.");
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Puente(), "Android");
        if (estado == null) web.loadUrl(INICIO);
        else web.restoreState(estado);
    }

    @Override
    protected void onSaveInstanceState(Bundle salida) {
        super.onSaveInstanceState(salida);
        web.saveState(salida);
    }

    @Override
    protected void onActivityResult(int codigo, int resultado, Intent datos) {
        super.onActivityResult(codigo, resultado, datos);
        if (codigo == RC_ARCHIVO) {
            if (subida == null) return;
            Uri[] r = null;
            if (resultado == RESULT_OK) {
                if (fotoUri != null) {
                    r = new Uri[]{fotoUri};
                } else if (datos != null && datos.getClipData() != null) {
                    ClipData c = datos.getClipData();
                    r = new Uri[c.getItemCount()];
                    for (int k = 0; k < c.getItemCount(); k++) r[k] = c.getItemAt(k).getUri();
                } else if (datos != null && datos.getData() != null) {
                    r = new Uri[]{datos.getData()};
                }
            }
            subida.onReceiveValue(r);
            subida = null;
            fotoUri = null;
        } else if (codigo == RC_VOZ) {
            String texto = "";
            if (resultado == RESULT_OK && datos != null) {
                ArrayList<String> l = datos.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (l != null && !l.isEmpty()) texto = l.get(0);
            }
            devolverVoz(texto);
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window._atras?window._atras():false", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    private void devolverVoz(String texto) {
        web.evaluateJavascript("window._vozNativa&&window._vozNativa(" + JSONObject.quote(texto) + ")", null);
    }

    private void aviso(String t) {
        Toast.makeText(this, t, Toast.LENGTH_LONG).show();
    }

    private File carpeta() {
        File d = new File(getCacheDir(), "compartir");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private Uri escribir(String b64, String nombre) throws Exception {
        String limpio = nombre.replaceAll("[^A-Za-z0-9._-]", "_");
        File f = new File(carpeta(), limpio);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(Base64.decode(b64, Base64.DEFAULT));
        }
        return FileProvider.getUriForFile(this, getPackageName() + ".archivos", f);
    }

    private void enviar(String b64, String nombre, String mime, String texto) {
        try {
            Uri u = escribir(b64, nombre);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, u);
            if (texto != null && !texto.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, texto);
            i.setClipData(ClipData.newRawUri(nombre, u));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Enviar " + nombre));
        } catch (Exception e) {
            aviso("No se ha podido preparar el archivo.");
        }
    }

    /** Lo que la app web puede pedirle al móvil. */
    private class Puente {
        @JavascriptInterface
        public void compartir(final String b64, final String nombre, final String mime, final String texto) {
            runOnUiThread(() -> enviar(b64, nombre, mime, texto));
        }

        @JavascriptInterface
        public void abrir(final String b64, final String nombre, final String mime) {
            runOnUiThread(() -> {
                try {
                    Uri u = escribir(b64, nombre);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(u, mime);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } catch (Exception e) {
                    // Sin visor de PDF en el móvil: se ofrece enviarlo o guardarlo.
                    enviar(b64, nombre, mime, "");
                }
            });
        }

        @JavascriptInterface
        public void dictar() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
                    i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla ahora");
                    startActivityForResult(i, RC_VOZ);
                } catch (Exception e) {
                    devolverVoz("");
                    aviso("Este móvil no tiene el dictado por voz de Google activado.");
                }
            });
        }
    }
}
