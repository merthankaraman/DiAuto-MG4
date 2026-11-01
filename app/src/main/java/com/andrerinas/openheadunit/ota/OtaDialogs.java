package com.andrerinas.openheadunit.ota;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.util.Log;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

final class OtaDialogs {

    private static final String TAG = "OtaDialogs";
    private static final int ACCENT_COLOR = 0xFF03A9F4;
    private static final int WARNING_COLOR = 0xFFFF9100;
    private static final float WARNING_SCALE = 1.3f;

    private OtaDialogs() {
    }

    /** The download size and the advice on what connection to use, in orange and larger, above [message]. */
    static CharSequence withDataWarning(String warning, String message) {
        SpannableStringBuilder text = new SpannableStringBuilder(warning);
        text.setSpan(new ForegroundColorSpan(WARNING_COLOR), 0, warning.length(), 0);
        text.setSpan(new RelativeSizeSpan(WARNING_SCALE), 0, warning.length(), 0);
        return text.append("\n\n").append(message);
    }

    static Dialog showConfirmDialog(
            Context context,
            CharSequence message,
            String confirmText,
            Runnable onConfirm
    ) {
        Dialog dialog = createBaseDialog(context, R.layout.dialog_ota_refresh);
        TextView messageView = dialog.findViewById(R.id.tvOtaRefreshMessage);
        TextView refreshButton = dialog.findViewById(R.id.btnOtaRefresh);
        View closeButton = dialog.findViewById(R.id.btnOtaClose);

        if (messageView != null) {
            messageView.setText(message);
        }
        if (refreshButton != null) {
            refreshButton.setText(confirmText);
            stylePrimaryButton(context, refreshButton);
            refreshButton.setOnClickListener(v -> {
                dialog.dismiss();
                if (onConfirm != null) onConfirm.run();
            });
        }
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dialog.dismiss());
        }

        showCentered(dialog, 540);
        return dialog;
    }

    static ProgressDialogHandle showProgressDialog(
            Context context,
            String titleText,
            Runnable onRetryDownload,
            Runnable onInstallUpdate
    ) {
        Dialog dialog = createBaseDialog(context, R.layout.dialog_ota_download_progress);
        TextView title = dialog.findViewById(R.id.tvOtaProgressTitle);
        TextView status = dialog.findViewById(R.id.tvOtaProgressStatus);
        ProgressBar progress = dialog.findViewById(R.id.pbOtaDownload);
        TextView retryDownloadButton = dialog.findViewById(R.id.btnOtaRetryDownload);
        TextView installUpdateButton = dialog.findViewById(R.id.btnOtaInstallUpdate);
        View closeButton = dialog.findViewById(R.id.btnOtaClose);

        if (title != null) {
            title.setText(titleText);
        }
        if (progress != null) {
            progress.setProgressTintList(ColorStateList.valueOf(ACCENT_COLOR));
            progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT_COLOR));
        }
        if (retryDownloadButton != null) {
            stylePrimaryButton(context, retryDownloadButton);
            retryDownloadButton.setOnClickListener(v -> {
                if (onRetryDownload != null) onRetryDownload.run();
            });
        }
        if (installUpdateButton != null) {
            stylePrimaryButton(context, installUpdateButton);
            installUpdateButton.setOnClickListener(v -> {
                if (onInstallUpdate != null) onInstallUpdate.run();
            });
        }
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dialog.dismiss());
        }

        showCentered(dialog, 560);
        return new ProgressDialogHandle(dialog, status, progress, retryDownloadButton, installUpdateButton);
    }

    static Dialog showMessageDialog(Context context, String message) {
        Dialog dialog = createBaseDialog(context, R.layout.dialog_ota_refresh);
        TextView messageView = dialog.findViewById(R.id.tvOtaRefreshMessage);
        View refreshButton = dialog.findViewById(R.id.btnOtaRefresh);
        View closeButton = dialog.findViewById(R.id.btnOtaClose);

        if (messageView != null) {
            messageView.setText(message);
        }
        if (refreshButton != null) {
            refreshButton.setVisibility(View.GONE);
        }
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dialog.dismiss());
        }

        showCentered(dialog, 540);
        return dialog;
    }

    private static Dialog createBaseDialog(Context context, int layoutResId) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(layoutResId);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        return dialog;
    }

    private static void showCentered(Dialog dialog, int widthDp) {
        if (!canShowDialog(dialog.getContext())) {
            Log.w(TAG, "skip show — host activity is finishing or destroyed");
            return;
        }
        try {
            dialog.show();
        } catch (WindowManager.BadTokenException e) {
            Log.w(TAG, "BadTokenException showing dialog (activity gone)");
            return;
        }
        Window window = dialog.getWindow();
        if (window == null) return;
        float density = dialog.getContext().getResources().getDisplayMetrics().density;
        int widthPx = (int) (widthDp * density);
        window.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static boolean canShowDialog(Context context) {
        Activity activity = findActivity(context);
        if (activity == null) return true;
        if (activity.isFinishing()) return false;
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed();
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private static void stylePrimaryButton(Context context, TextView button) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(14f * context.getResources().getDisplayMetrics().density);
        background.setColor(ACCENT_COLOR);
        button.setBackground(background);
        button.setTextColor(Color.WHITE);
    }

    static final class ProgressDialogHandle {
        final Dialog dialog;
        final TextView statusView;
        final ProgressBar progressBar;
        final View retryButton;
        final View installButton;

        ProgressDialogHandle(Dialog dialog, TextView statusView, ProgressBar progressBar,
                             View retryButton, View installButton) {
            this.dialog = dialog;
            this.statusView = statusView;
            this.progressBar = progressBar;
            this.retryButton = retryButton;
            this.installButton = installButton;
        }
    }
}
