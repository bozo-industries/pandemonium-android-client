package page.claras.pandemonium;

import android.graphics.Bitmap;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import java.util.EnumMap;
import java.util.Map;

final class LoyaltyQrRenderer {
    private LoyaltyQrRenderer() { }

    static Bitmap render(String payload, int sizePx) throws WriterException {
        if (payload == null || payload.trim().isEmpty() || sizePx < 64) throw new IllegalArgumentException("Invalid QR input");
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 4);
        BitMatrix matrix = new MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
        int[] pixels = new int[sizePx * sizePx];
        for (int y = 0; y < sizePx; y++) {
            for (int x = 0; x < sizePx; x++) pixels[y * sizePx + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
        }
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx);
        return bitmap;
    }
}
