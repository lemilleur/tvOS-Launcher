package com.techgenius.tvoslauncher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * First-time setup, in the style of Apple TV's own setup: one question per page, a large title
 * and a short line of help at the top, choices as a centred list (white when focused), OK moves
 * on and Back goes back. Pages: Welcome (in many languages), your profile, look, top shelf, weather, presentation,
 * home screen & permissions, done. Shown once; Settings \u2192 Home \u2192 Run Setup Again repeats it.
 */
public class SetupActivity extends Activity {
    static final String K_DONE = "setup_done";

    FrameLayout root;
    ImageView backdrop;
    LinearLayout page;
    TextView skipBtn;
    int index = 0;
    int lookSubStep = 0; // 0 = choose appearance, 1 = choose scenes, 2 = choose classic background
    final Handler handler = new Handler(Looper.getMainLooper());

    interface Step {
        void build();
    }

    final List<Step> steps = new ArrayList<>();

    static boolean done(Activity a) {
        return a.getSharedPreferences("setup", MODE_PRIVATE).getBoolean(K_DONE, false);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Clicks.init(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View dim = new View(this);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x66000000, 0xB3000000}));
        root.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setClipChildren(true);
        page.setClipToPadding(true);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        page.setPadding(0, u(36), 0, u(22));
        root.addView(page, plp);

        // Top-right "Skip Setup" pill button on the first setup screen (language selection)
        skipBtn = new TextView(this);
        skipBtn.setId(View.generateViewId());
        skipBtn.setText(tr("Skip Setup") + "  \u203a");
        skipBtn.setTextColor(Color.WHITE);
        skipBtn.setTypeface(Ui.font(this, "medium"));
        Ui.textSize(skipBtn, 14);
        skipBtn.setGravity(Gravity.CENTER);
        skipBtn.setFocusable(true);
        skipBtn.setClickable(true);
        skipBtn.setPadding(u(18), u(9), u(18), u(9));
        GradientDrawable skipBg = new GradientDrawable();
        skipBg.setCornerRadius(u(999));
        skipBg.setColor(0x2EFFFFFF);
        skipBg.setStroke(Math.max(1, u(1)), 0x40FFFFFF);
        skipBtn.setBackground(skipBg);
        skipBtn.setOnFocusChangeListener((v, has) -> {
            skipBg.setColor(has ? 0xF2FFFFFF : 0x2EFFFFFF);
            skipBg.setStroke(Math.max(1, u(1)), has ? Color.WHITE : 0x40FFFFFF);
            skipBtn.setTextColor(has ? 0xFF111114 : Color.WHITE);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(180).start();
        });
        skipBtn.setOnClickListener(v -> {
            Scenes.setClock24h(this, true);
            getSharedPreferences("setup", MODE_PRIVATE).edit().putBoolean(K_DONE, true).putInt("cur_step", 0).apply();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });
        FrameLayout.LayoutParams skipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        skipLp.topMargin = u(26);
        skipLp.rightMargin = u(36);
        root.addView(skipBtn, skipLp);

        setContentView(root);

        steps.add(this::hello);
        steps.add(this::profile);
        steps.add(this::look);
        steps.add(this::weather);
        steps.add(this::presentation);
        steps.add(this::homeAndPermissions);
        steps.add(this::defaultHomeSetup);
        steps.add(this::buttonMapperSetup);
        steps.add(this::finish_);
        if (getIntent().getBooleanExtra("skip", false)) {  // adb: --ez skip true marks setup as done
            getSharedPreferences("setup", MODE_PRIVATE).edit().putBoolean(K_DONE, true).putInt("cur_step", 0).apply();
            finish();
            return;
        }
        int savedStep = getSharedPreferences("setup", MODE_PRIVATE).getInt("cur_step", 0);
        show(getIntent().getIntExtra("step", savedStep), 0);  // adb: --ei step N opens a page directly
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int savedStep = getSharedPreferences("setup", MODE_PRIVATE).getInt("cur_step", index);
        int target = intent.getIntExtra("step", savedStep);
        if (target != index) {
            show(target, 0);
        } else if (index == 5 || index == 6 || index == 7) {
            show(index, 0);
        }
    }

    int u(float v) {
        return Ui.px(this, v);
    }

    // ---------------------------------------------------------------- navigation

    void show(int i, int dir) {
        handler.removeCallbacksAndMessages(null);
        if (i != 2) lookSubStep = 0;
        index = Math.max(0, Math.min(i, steps.size() - 1));
        getSharedPreferences("setup", MODE_PRIVATE).edit().putInt("cur_step", index).apply();
        if (skipBtn != null) {
            skipBtn.setText(tr("Skip Setup") + "  \u203a");
            skipBtn.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        }
        page.animate().cancel();
        page.removeAllViews();
        steps.get(index).build();
        // New page: slide and fade. Same page re-drawn after a switch: a quick fade only.
        page.setAlpha(dir == 0 ? 0.6f : 0f);
        page.setTranslationX(u(30) * dir);
        page.animate().alpha(1f).translationX(0).setDuration(dir == 0 ? 120 : 320).start();
    }

    void next() {
        show(index + 1, 1);
    }

    @Override
    public void onBackPressed() {
        if (index == 2 && lookSubStep != 0) {
            lookSubStep = 0;
            show(2, -1);
            return;
        }
        if (index == 5 && permMode != 0) {
            permMode = 0;
            show(5, -1);
            return;
        }
        if (index > 0) show(index - 1, -1);
        // The first page has nowhere to go back to: setup stays until it is finished.
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (index == 5 || index == 6) show(index, 0);  // refresh the ticks
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (index == 5 || index == 6 || index == 7) show(index, 0);
    }

    // ---------------------------------------------------------------- building blocks

    TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Ui.font(this, "display"));
        t.setLetterSpacing(-0.02f);
        Ui.textSize(t, 34);
        t.setGravity(Gravity.CENTER);
        page.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    TextView subtitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xB3FFFFFF);
        t.setTypeface(Ui.font(this, "regular"));
        Ui.textSize(t, 14);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(520), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = u(10);
        lp.bottomMargin = u(26);
        page.addView(t, lp);
        return t;
    }

    LinearLayout list() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        l.setPadding(u(22), u(6), u(22), u(6));
        if (page.findViewWithTag("list") == null) l.setTag("list");
        page.addView(l, new LinearLayout.LayoutParams(u(484), ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    LinearLayout scrollableList() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(true);
        sv.setClipToPadding(true);
        sv.setTag("scroll_container");
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        l.setPadding(u(22), u(6), u(22), u(6));
        if (page.findViewWithTag("list") == null) l.setTag("list");
        sv.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(u(484), 0, 1f);
        page.addView(sv, svLp);
        return l;
    }

    /** A choice row: title, optional detail and a tick; white with dark text when focused. */
    View option(LinearLayout list, String label, String detail, boolean checked, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(u(18), u(8), u(16), u(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(u(12));
        bg.setColor(0x26FFFFFF);
        r.setBackground(bg);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Color.WHITE);
        l.setTypeface(Ui.font(this, "medium"));
        Ui.textSize(l, 15);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(l);
        TextView d = null;
        if (detail != null && !detail.isEmpty()) {
            d = new TextView(this);
            d.setText(detail);
            d.setTextColor(0x99FFFFFF);
            d.setTypeface(Ui.font(this, "regular"));
            Ui.textSize(d, 12);
            d.setSingleLine(true);
            d.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = u(2);
            texts.addView(d, dlp);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tick = new TextView(this);
        tick.setText(checked ? "\u2713" : "");
        tick.setTextColor(0xFF30D158);
        tick.setTypeface(Ui.font(this, "semibold"));
        Ui.textSize(tick, 17);
        LinearLayout.LayoutParams tickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tickLp.leftMargin = u(10);
        r.addView(tick, tickLp);
        TextView dRef = d;
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x26FFFFFF);
            l.setTextColor(has ? 0xFF111114 : Color.WHITE);
            if (dRef != null) dRef.setTextColor(has ? 0x99111114 : 0x99FFFFFF);
            tick.setTextColor(has ? 0xFF1E9E48 : 0xFF30D158);
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(180).start();
        });
        r.setOnClickListener(v -> action.run());
        r.setMinimumHeight(d == null ? u(46) : u(56));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = u(8);
        list.addView(r, lp);
        if (list.getChildCount() == 1 && "list".equals(list.getTag())) r.requestFocus();
        return r;
    }

    void dots() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 1; i < steps.size() - 1; i++) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i == index ? Color.WHITE : 0x59FFFFFF);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(6), u(6));
            lp.leftMargin = lp.rightMargin = u(4);
            d.addView(dot, lp);
        }
        if (page.findViewWithTag("scroll_container") == null) {
            View spacer = new View(this);
            page.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        }
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = u(12);
        page.addView(d, dlp);
    }

    void setBackdrop(String style) {
        Bitmap b = null;
        if ("scenes".equals(style)) {
            b = Scenes.loadAssetBitmap(this, "backgrounds/scene-night.jpg", 1280, 720);
        } else if (style != null && style.startsWith("scene_asset:")) {
            b = Scenes.loadAssetBitmap(this, style.substring(12), 1280, 720);
        } else if (style != null && style.startsWith("photo:")) {
            b = Scenes.loadAssetBitmap(this, "backgrounds/" + style.substring(6), 1280, 720);
        }
        if (b == null) {
            b = MainActivity.makeWallpaperBase(style != null ? style : "aurora");
        }
        backdrop.setImageDrawable(new BitmapDrawable(getResources(), b));
        backdrop.animate().alpha(1f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- pages

    String tr(String en, String trStr) {
        return I18n.t(this, en);
    }

    String tr(String key) {
        return I18n.t(this, key);
    }

    /** Welcome, then Get Started. */
    void hello() {
        backdrop.animate().alpha(0f).setDuration(300).start();
        TextView hi = title(tr("Welcome"));
        Ui.textSize(hi, 52);
        hi.setAlpha(0f);
        hi.setTranslationY(u(12));
        hi.animate().alpha(1f).translationY(0).setDuration(700).start();
        TextView s = subtitle(tr("Let\u2019s set up tvOS Launcher."));
        Ui.textSize(s, 16);
        ((LinearLayout.LayoutParams) s.getLayoutParams()).bottomMargin = u(16);
        LinearLayout l = scrollableList();
        View startRow = option(l, tr("Get Started"), null, false, () -> {
            Scenes.setClock24h(this, true);
            next();
        });
        if (skipBtn != null) {
            startRow.setId(View.generateViewId());
            startRow.setNextFocusUpId(skipBtn.getId());
            startRow.setNextFocusRightId(skipBtn.getId());
            skipBtn.setNextFocusDownId(startRow.getId());
            skipBtn.setNextFocusLeftId(startRow.getId());
        }
        TextView by = new TextView(this);
        by.setText("Оригинал: techgenius · Русский форк: lemilleur");
        by.setTextColor(0x80FFFFFF);
        by.setTypeface(Ui.font(this, "regular"));
        Ui.textSize(by, 13);
        by.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams byLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        byLp.topMargin = u(12);
        page.addView(by, byLp);
    }

    void profile() {
        title(tr("Create Your Profile"));
        subtitle(tr("Your name and an avatar. Each profile keeps its own apps, background and Home Screen. Up to three people can have one."));
        Profiles.Profile p = Profiles.current(this);
        LinearLayout l = list();
        View nameRow = option(l, tr("Name"), I18n.profileName(this, p.name), false, () -> {
            EditText in = new EditText(this);
            in.setSingleLine(true);
            in.setText(p.name);
            in.setSelection(in.getText().length());
            int pad = u(14);
            FrameLayout box = new FrameLayout(this);
            box.setPadding(pad, pad / 2, pad, 0);
            box.addView(in);
            android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(tr("Your name")).setView(box)
                    .setPositiveButton(tr("OK"), (d, w) -> {
                        String n = in.getText().toString().trim();
                        if (!n.isEmpty()) {
                            p.name = n;
                            Profiles.update(this, p);
                        }
                        show(index, 0);
                    }).setNegativeButton(tr("Cancel"), null).show();
            Ui.setupDialogEditText(in, dlg);
            in.requestFocus();
        });
        int nameId = View.generateViewId();
        nameRow.setId(nameId);

        // Avatar: a row of emoji, the current one ringed.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        List<String> choices = new ArrayList<>(Profiles.freeEmojis(this, p.id));
        if (!choices.contains(p.emoji)) choices.add(0, p.emoji);
        int count = Math.min(8, choices.size());
        int[] emojiIds = new int[count];
        TextView[] emojiViews = new TextView[count];
        GradientDrawable[] emojiBgs = new GradientDrawable[count];
        int activeEmojiId = View.NO_ID;
        for (int i = 0; i < count; i++) {
            final int idx = i;
            String e = choices.get(i);
            TextView t = new TextView(this);
            int eid = View.generateViewId();
            t.setId(eid);
            emojiIds[i] = eid;
            emojiViews[i] = t;
            if (e.equals(p.emoji) || activeEmojiId == View.NO_ID) activeEmojiId = eid;
            t.setText(e);
            Ui.textSize(t, 26);
            t.setGravity(Gravity.CENTER);
            t.setFocusable(true);
            t.setClickable(true);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(e.equals(p.emoji) ? 0x59FFFFFF : 0x1FFFFFFF);
            emojiBgs[i] = g;
            t.setBackground(g);
            t.setOnFocusChangeListener((v, has) -> {
                g.setStroke(has ? u(2) : 0, Color.WHITE);
                v.animate().scaleX(has ? 1.2f : 1f).scaleY(has ? 1.2f : 1f).setDuration(160).start();
            });
            t.setOnClickListener(v -> {
                p.emoji = e;
                Profiles.update(this, p);
                for (int j = 0; j < count; j++) {
                    boolean sel = choices.get(j).equals(p.emoji);
                    emojiBgs[j].setColor(sel ? 0x59FFFFFF : 0x1FFFFFFF);
                }
                nameRow.setNextFocusDownId(emojiIds[idx]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(46), u(46));
            lp.leftMargin = lp.rightMargin = u(5);
            row.addView(t, lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = u(6);
        rlp.bottomMargin = u(14);
        page.addView(row, rlp);
        LinearLayout l2 = list();
        View contRow = option(l2, tr("Continue"), null, false, this::next);
        int contId = View.generateViewId();
        contRow.setId(contId);

        if (activeEmojiId != View.NO_ID) {
            nameRow.setNextFocusDownId(activeEmojiId);
            contRow.setNextFocusUpId(activeEmojiId);
            for (int i = 0; i < count; i++) {
                emojiViews[i].setNextFocusUpId(nameId);
                emojiViews[i].setNextFocusDownId(contId);
                if (i > 0) emojiViews[i].setNextFocusLeftId(emojiIds[i - 1]);
                if (i + 1 < count) emojiViews[i].setNextFocusRightId(emojiIds[i + 1]);
            }
        }
        nameRow.requestFocus();
        dots();
    }

    /** Step 1: Choose Appearance (Night scenes vs Classic). Step 2: Choose Scenes or Classic Background. */
    void look() {
        boolean scenesMode = Scenes.isScenesLook(this);
        String curWall = Profiles.homePrefs(this).getString("wallpaper", "photo:tessellated-pavement.jpg");
        if (lookSubStep == 0) {
            title(tr("Appearance"));
            TextView s = subtitle(tr("Choose how you want your Home Screen to look."));
            ((LinearLayout.LayoutParams) s.getLayoutParams()).bottomMargin = u(16);
            setBackdrop(scenesMode ? "scenes" : curWall);
            LinearLayout l = list();
            View scenesRow = option(l, tr("Night scenes"),
                    tr("Rotating nature scenes with a glass clock and bottom dock"),
                    scenesMode, () -> {
                        Scenes.setLook(this, Scenes.LOOK_SCENES);
                        lookSubStep = 1;
                        show(index, 1);
                    });
            View.OnFocusChangeListener scenesBase = scenesRow.getOnFocusChangeListener();
            scenesRow.setOnFocusChangeListener((v, has) -> {
                scenesBase.onFocusChange(v, has);
                if (has) setBackdrop("scenes");
            });

            View classicRow = option(l, tr("Classic"),
                    tr("Top shelf, 6-column app grid and custom wallpapers"),
                    !scenesMode, () -> {
                        Scenes.setLook(this, Scenes.LOOK_CLASSIC);
                        lookSubStep = 2;
                        show(index, 1);
                    });
            View.OnFocusChangeListener classicBase = classicRow.getOnFocusChangeListener();
            classicRow.setOnFocusChangeListener((v, has) -> {
                classicBase.onFocusChange(v, has);
                if (has) setBackdrop(curWall);
            });
            if (scenesMode) scenesRow.requestFocus();
            else classicRow.requestFocus();
            dots();
            return;
        }

        if (lookSubStep == 1) {
            title(tr("Choose Your Scenes"));
            TextView s = subtitle(tr("Choose which scenes take turns behind the glass clock."));
            ((LinearLayout.LayoutParams) s.getLayoutParams()).bottomMargin = u(16);
            setBackdrop("scene_asset:" + Scenes.ALL[0].bgAsset);
            LinearLayout l = scrollableList();
            for (int i = 0; i < Scenes.ALL.length; i++) {
                final Scenes.Spec spec = Scenes.ALL[i];
                boolean on = Scenes.isSceneEnabled(this, spec.id);
                final View[] rowRef = new View[1];
                View r = option(l, spec.title(this),
                        on ? tr("Included in rotation") : tr("Off"),
                        on, () -> {
                            if (Scenes.toggleScene(this, spec.id)) {
                                boolean nowOn = Scenes.isSceneEnabled(this, spec.id);
                                if (rowRef[0] instanceof ViewGroup) {
                                    ViewGroup vg = (ViewGroup) rowRef[0];
                                    if (vg.getChildCount() >= 2) {
                                        View texts = vg.getChildAt(0);
                                        if (texts instanceof ViewGroup && ((ViewGroup) texts).getChildCount() >= 2) {
                                            TextView d = (TextView) ((ViewGroup) texts).getChildAt(1);
                                            d.setText(nowOn ? tr("Included in rotation") : tr("Off"));
                                        }
                                        View tick = vg.getChildAt(1);
                                        if (tick instanceof TextView) {
                                            ((TextView) tick).setText(nowOn ? "\u2713" : "");
                                        }
                                    }
                                }
                            }
                        });
                rowRef[0] = r;
                View.OnFocusChangeListener base = r.getOnFocusChangeListener();
                r.setOnFocusChangeListener((v, has) -> {
                    base.onFocusChange(v, has);
                    if (has) setBackdrop("scene_asset:" + spec.bgAsset);
                });
                if (i == 0) r.requestFocus();
            }
            option(l, tr("Continue"), null, false, () -> {
                lookSubStep = 0;
                next();
            });
            dots();
            return;
        }

        // lookSubStep == 2: Classic Backgrounds
        title(tr("Choose a Background"));
        TextView s = subtitle(tr("Choose a photo or ambient colour theme behind your apps."));
        ((LinearLayout.LayoutParams) s.getLayoutParams()).bottomMargin = u(16);
        setBackdrop(curWall);
        LinearLayout l = scrollableList();
        for (int i = 0; i < MainActivity.WALL_KEYS.length; i++) {
            String key = MainActivity.WALL_KEYS[i];
            String name = MainActivity.wallName(this, i);
            boolean checked = key.equals(curWall);
            View r = option(l, name,
                    key.startsWith("photo:") ? tr("Classic photo") : tr("Classic look"),
                    checked, () -> {
                        Scenes.setLook(this, Scenes.LOOK_CLASSIC);
                        Profiles.homePrefs(this).edit().putString("wallpaper", key).apply();
                        lookSubStep = 0;
                        next();
                    });
            View.OnFocusChangeListener base = r.getOnFocusChangeListener();
            r.setOnFocusChangeListener((v, has) -> {
                base.onFocusChange(v, has);
                if (has) setBackdrop(key);
            });
            if (checked) r.requestFocus();
        }
        dots();
    }

    /** This profile's Home Screen: recommendations and/or news on the top shelf, as two switches. */
    void homeContent() {
        backdrop.animate().alpha(0.5f).setDuration(300).start();
        title(tr("Your Home Screen"));
        subtitle(tr("What appears at the top of Home, behind your apps. Choose any, all or none."));
        boolean recs = Suggestions.showRecs(this);
        LinearLayout l = list();
        option(l, tr("Recommendations"),
                tr("Popular movies and series on your streaming services"), recs, () -> {
            Suggestions.setShelf(this, recs ? Suggestions.SHELF_NONE : Suggestions.SHELF_RECS);
            show(index, 0);
            focusRow(0);
        });
        boolean cw = Profiles.homePrefs(this).getBoolean("cw_enabled", true);
        option(l, tr("Continue Watching"),
                tr("What you were watching, mixed into the carousel"), cw, () -> {
            Profiles.homePrefs(this).edit().putBoolean("cw_enabled", !cw).apply();
            show(index, 0);
            focusRow(1);
        });
        option(l, tr("Continue"), recs || cw ? null : tr("Just your background for now"), false, this::next);
        dots();
    }

    /** After re-building a page, put focus back on the row that was pressed. */
    void focusRow(int i) {
        View v = page.findViewWithTag("list");
        if (v instanceof LinearLayout && i < ((LinearLayout) v).getChildCount()) ((LinearLayout) v).getChildAt(i).requestFocus();
    }

    void weather() {
        title(tr("Weather"));
        subtitle(tr("A small weather display on Home, with a 5-day forecast."));
        LinearLayout l = list();
        String city = Weather.place(this);
        option(l, tr("Use My Location"),
                city.isEmpty() ? tr("Found from your internet connection") : tr("Looks like ") + city,
                Weather.enabled(this) && !Weather.manual(this), () -> {
                    Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, true).apply();
                    Weather.setPlace(this, null);
                    next();
                });
        option(l, tr("Choose a City\u2026"),
                Weather.manual(this) ? Weather.place(this) : tr("Search by name"), Weather.manual(this), this::searchCityDialog);
        option(l, tr("Next"), null, false, this::next);
        option(l, tr("Don't Show Weather"), null, !Weather.enabled(this), () -> {
            Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, false).apply();
            Weather.changed(this);
            next();
        });
        if (city.isEmpty()) Weather.refresh(this, true, () -> {
            if (index == 3) show(3, 0);  // show the detected city
        });
        dots();
    }

    private void searchCityDialog() {
        EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint("Название города, например Москва");
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        int pad = u(14);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(in);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(tr("Choose City"))
                .setView(box)
                .setPositiveButton(tr("Search"), (d, w) -> {
                    String q = in.getText().toString().trim();
                    if (q.isEmpty()) return;
                    new Thread(() -> {
                        List<Weather.Place> found;
                        try {
                            found = Weather.search(this, q);
                        } catch (Exception e) {
                            found = null;
                        }
                        List<Weather.Place> res = found;
                        runOnUiThread(() -> showCityMatches(q, res));
                    }).start();
                })
                .setNegativeButton(tr("Cancel"), null)
                .show();
        Ui.setupDialogEditText(in, dlg);
        in.requestFocus();
    }

    private void showCityMatches(String q, List<Weather.Place> res) {
        if (isFinishing()) return;
        if (res == null || res.isEmpty()) {
            android.widget.Toast.makeText(this,
                    res == null
                            ? tr("Couldn't search. Check the internet connection.")
                            : (tr("No place found for \u201c") + q + "\u201d."),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[res.size()];
        for (int i = 0; i < names.length; i++) {
            Weather.Place pl = res.get(i);
            names[i] = pl.detail.isEmpty() ? pl.name : pl.name + "  \u00b7  " + pl.detail;
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(tr("Matching places"))
                .setItems(names, (d, w) -> {
                    Weather.Place picked = res.get(w);
                    Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, true).apply();
                    Weather.setPlace(this, picked);
                    Weather.refresh(this, true, null);
                    show(index, 0);
                    focusRow(2);
                })
                .setNegativeButton(tr("Cancel"), null)
                .show();
    }

    /** Presentation is for the whole TV: switch it on and choose its options here, once. */
    void presentation() {
        ImageView icon = new ImageView(this);
        icon.setImageBitmap(com.techgenius.tvoslauncher.present.PresentIcon.banner(u(90) * 2, u(54) * 2));
        icon.setClipToOutline(true);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(u(9));
        icon.setBackground(shape);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(u(90), u(54));
        ilp.bottomMargin = u(10);
        page.addView(icon, ilp);
        title(tr("Presentation"));
        subtitle(tr("Images, videos and webpages sent from any phone or computer on the same Wi-Fi. For everyone on this TV."));
        boolean on = com.techgenius.tvoslauncher.present.Presenter.enabled(this);
        LinearLayout l = list();
        option(l, tr("Use Presentation"),
                on ? tr("Adds a Presentation card to Home") : tr("Off"), on, () -> {
            com.techgenius.tvoslauncher.present.Presenter.set(this, com.techgenius.tvoslauncher.present.Presenter.KEY_ENABLED, !on);
            show(index, 0);
            focusRow(0);
        });
        if (on) {
            boolean boot = com.techgenius.tvoslauncher.present.Presenter.startOnBoot(this),
                    awake = com.techgenius.tvoslauncher.present.Presenter.keepAwake(this);
            option(l, tr("Start with the TV"),
                    tr("Play as soon as the TV turns on (signage)"), boot, () -> {
                com.techgenius.tvoslauncher.present.Presenter.set(this, com.techgenius.tvoslauncher.present.Presenter.KEY_BOOT, !boot);
                show(index, 0);
                focusRow(1);
            });
            option(l, tr("Keep Screen Awake"),
                    tr("No screensaver or sleep while presenting"), awake, () -> {
                com.techgenius.tvoslauncher.present.Presenter.set(this, com.techgenius.tvoslauncher.present.Presenter.KEY_AWAKE, !awake);
                show(index, 0);
                focusRow(2);
            });
        }
        option(l, tr("Continue"), null, false, this::next);
        dots();
    }

    boolean askedLocation;
    int permMode = 0; // 0 = Choose Usage Mode (3 modes), 1 = Easy ADB Directions, 2 = Manual Setup

    void openDefaultHomeSubMenu() {
        show(6, 1);
    }

    void homeAndPermissions() {
        if (permMode == 0) {
            title(tr("Finish Setting Up"));
            TextView s = subtitle(tr("Select a usage mode for permissions (Button Mapper Accessibility, Continue Watching, and Wi-Fi). Pick ONE usage mode below:"));
            ((LinearLayout.LayoutParams) s.getLayoutParams()).bottomMargin = u(14);
            LinearLayout l = scrollableList();
            option(l, tr("Mode: Secure Auto Setup & Close ADB (Recommended)"),
                    tr("Grants WRITE_SECURE_SETTINGS once to self-repair Accessibility, then closes port 5555"),
                    false,
                    () -> {
                        LocalAdb.setAdbMode(this, LocalAdb.ADB_MODE_SECURE_CLOSE);
                        proceedToEasyAdbOrDirections();
                    });
            option(l, tr("Mode: Persistent ADB Setup (Keep ADB Open)"),
                    tr("Keeps network ADB enabled for CleanYourTV debloating (Note: leaves port 5555 open on LAN)"),
                    false,
                    () -> {
                        LocalAdb.setAdbMode(this, LocalAdb.ADB_MODE_PERSISTENT);
                        proceedToEasyAdbOrDirections();
                    });
            option(l, tr("Mode: Manual Setup (Without ADB)"),
                    tr("Grant permissions one by one manually through Android TV Settings"),
                    false,
                    () -> {
                        permMode = 2;
                        show(5, 1);
                    });
            option(l, tr("Continue"), null, false, this::next);
            dots();
            return;
        }

        if (permMode == 1) {
            boolean secureClose = LocalAdb.getAdbMode(this) == LocalAdb.ADB_MODE_SECURE_CLOSE;
            title(tr("Enable ADB Debugging"));
            TextView s = subtitle((tr("1. Open TV Settings \u2192 System \u2192 About and click Build 7 times to unlock Developer Options.\n")
                    + tr("2. Open Settings \u2192 System \u2192 Developer Options and enable USB Debugging / Network Debugging.\n")
                    + tr("3. Return here and select Grant Permissions Now")
                    + (secureClose ? tr(" (ADB port 5555 will be closed automatically right after setup).") : tr(" (ADB will remain enabled for CleanYourTV)."))));
            ((LinearLayout.LayoutParams) s.getLayoutParams()).width = u(620);
            ((LinearLayout.LayoutParams) s.getLayoutParams()).bottomMargin = u(14);
            LinearLayout l = scrollableList();
            option(l, tr("Open Developer Options / TV Settings\u2026"),
                    tr("Enable USB / Network Debugging in Developer Options"),
                    LocalAdb.isAdbSettingEnabled(this),
                    () -> {
                        Intent dev = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                        if (!Suggestions.start(this, dev)) {
                            Intent about = new Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS);
                            if (!Suggestions.start(this, about)) {
                                Suggestions.start(this, new Intent(android.provider.Settings.ACTION_SETTINGS));
                            }
                        }
                    });
            option(l, secureClose
                            ? tr("Grant Permissions & Close ADB Now")
                            : tr("Grant Permissions Now (Keep ADB Open)"),
                    secureClose
                            ? tr("Grants WRITE_SECURE_SETTINGS & closes port 5555 when done")
                            : tr("Uses local ADB to grant permissions and keeps ADB open"),
                    false,
                    this::runEasyAdbGrant);
            option(l, tr("Back to Usage Mode Selection"),
                    tr("Switch between Secure Auto Setup, Persistent ADB, or Manual Setup"),
                    false,
                    () -> {
                        permMode = 0;
                        show(5, -1);
                    });
            dots();
            return;
        }

        // permMode == 2: Manual Setup
        if (!Perms.hasLocation(this) && !askedLocation) {
            askedLocation = true;
            handler.postDelayed(() -> Perms.requestLocation(this), 600);
        }
        title(tr("Finish Setting Up"));
        TextView s = subtitle(tr("tvOS Launcher works best as the home screen. Allow these once; anything this TV doesn't support is skipped."));
        ((LinearLayout.LayoutParams) s.getLayoutParams()).bottomMargin = u(16);
        LinearLayout l = scrollableList();
        boolean home = Perms.isDefaultHome(this);
        option(l, tr("Make tvOS Launcher the Home Screen"),
                home ? tr("The Home button opens tvOS Launcher") : tr("Choose tvOS Launcher when asked"), home,
                () -> Perms.requestDefaultHome(this));
        if (!Perms.hasLocation(this)) {
            option(l, tr("Show the Wi-Fi Name"),
                    tr("In Control Center (needs location access)"), false,
                    () -> Perms.requestLocation(this));
        }
        if (Perms.listenerSupported(this) && !Perms.hasListener(this)) {
            option(l, tr("Continue Watching"),
                    tr("Remember what you play in each app"), false, () -> Perms.requestListener(this));
        }
        boolean accOn = ButtonMapper.isServiceEnabled(this);
        option(l, tr("Button Mapper & Control Panel"),
                accOn
                        ? tr("Active in Accessibility")
                        : tr("Enable in Accessibility so remapped buttons work in all apps"),
                accOn,
                () -> {
                    Intent acc = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    if (!Suggestions.start(this, acc)) {
                        Suggestions.start(this, new Intent(android.provider.Settings.ACTION_SETTINGS));
                    }
                });
        option(l, tr("Back to Usage Mode Selection"), null, false, () -> {
            permMode = 0;
            show(5, -1);
        });
        option(l, tr("Continue"), null, false, this::next);
        dots();
    }

    /** Dedicated page right after permissions: Make tvOS Launcher Default Home (with sequential troubleshooting steps). */
    void defaultHomeSetup() {
        title(tr("Make tvOS Launcher Default Home"));
        TextView s = subtitle(tr("On TCL, Philips, and Google TVs, OEM firmware often forces the stock launcher when pressing Home. Follow the steps below so pressing Home always opens tvOS Launcher, then click Continue."));
        ((LinearLayout.LayoutParams) s.getLayoutParams()).width = u(620);
        ((LinearLayout.LayoutParams) s.getLayoutParams()).bottomMargin = u(14);
        LinearLayout l = scrollableList();

        boolean isDef = Perms.isDefaultHome(this);
        option(l, tr("Step 1: Set tvOS Launcher as Default via Local ADB"),
                isDef ? tr("Active — Set as default home launcher")
                      : tr("Run ROLE_HOME & set-home-activity over Local ADB ›"),
                isDef,
                () -> runAdbTroubleshootStep(this::executeStep1SetDefaultLauncherViaAdb));

        option(l, tr("Step 2: Disable System Launchers (Google TV / Android TV / OEM)"),
                CleanYourTv.getSystemLaunchersSummary(this),
                !Perms.hasEnabledStockLauncher(this),
                () -> runAdbTroubleshootStep(() -> CleanYourTv.showDisableSystemLaunchersDialog(this, () -> show(index, 0))));

        boolean homeFixOn = ButtonMapper.isHomeFixEnabled(this);
        option(l, tr("Step 3: Fix Home Button via Accessibility (Button Mapper)"),
                homeFixOn
                        ? tr("On — Pressing Home launches tvOS Launcher")
                        : tr("Off — Tap to enable Home button remap"),
                homeFixOn,
                () -> {
                    boolean next = !ButtonMapper.isHomeFixEnabled(this);
                    ButtonMapper.setHomeFixEnabled(this, next);
                    if (next && !ButtonMapper.isServiceEnabled(this)) {
                        if (!LocalAdb.hasWriteSecureSettings(this) || !LocalAdb.repairPermissionsViaSecureSettings(this)) {
                            Intent acc = Perms.accessibilityScreen(this);
                            if (acc != null) {
                                Suggestions.start(this, acc);
                            }
                        }
                    }
                    show(index, 0);
                });

        option(l, tr("Step 4: Open Android System Home Settings"),
                tr("Open system selector (if supported by TV) ›"),
                false,
                () -> Perms.openSystemHomeSettingsUi(this));

        option(l, tr("Continue"),
                tr("Proceed to the next setup step when you are done here"),
                false,
                this::next);
        dots();
    }

    private void executeStep1SetDefaultLauncherViaAdb() {
        android.app.AlertDialog progress = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(tr("Setting Default Launcher…"))
                .setMessage(tr("Setting ROLE_HOME and default home activity over Local ADB…"))
                .setCancelable(false)
                .create();
        progress.show();
        LocalAdb.setDefaultLauncherViaAdb(this, (success, msg) -> runOnUiThread(() -> {
            if (isFinishing()) return;
            try {
                progress.dismiss();
            } catch (Throwable ignored) {
            }
            // Refresh current page without skipping so the user can proceed to the next step on this page
            show(index, 0);
            android.widget.Toast.makeText(this,
                    success
                            ? tr("Step 1 complete: Default home launcher set via ADB!")
                            : tr("Could not connect to ADB — enable USB/Network Debugging or use Step 3 / Step 4."),
                    android.widget.Toast.LENGTH_LONG).show();
        }));
    }

    /**
     * If the user selected Secure ADB Mode on the previous page (or ADB is currently off),
     * prompts them that ADB mode is required for this troubleshooting step and lets them
     * either Enable ADB Now or Skip This Step.
     */
    private void runAdbTroubleshootStep(Runnable stepAction) {
        boolean secureModeSelected = LocalAdb.getAdbMode(this) == LocalAdb.ADB_MODE_SECURE_CLOSE;
        boolean adbOff = !LocalAdb.isAdbSettingEnabled(this) && !LocalAdb.portReachable;
        if (secureModeSelected || adbOff) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(tr("ADB Mode Required"))
                    .setMessage(tr("You need the ADB mode enabled for this troubleshooting step. You can always change the ADB mode from Settings later.\n\nWould you like to enable ADB now or skip this step?"))
                    .setPositiveButton(tr("Enable ADB Now"), (d, w) -> {
                        LocalAdb.setAdbMode(this, LocalAdb.ADB_MODE_PERSISTENT);
                        android.app.AlertDialog progress = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle(tr("Enabling ADB…"))
                                .setMessage(tr("Turning on ADB debugging for this troubleshooting step…"))
                                .setCancelable(false)
                                .create();
                        progress.show();
                        LocalAdb.enableAdbPort(this, (success, msg) -> runOnUiThread(() -> {
                            if (isFinishing()) return;
                            try {
                                progress.dismiss();
                            } catch (Throwable ignored) {
                            }
                            show(index, 0);
                            if (success || LocalAdb.isAdbSettingEnabled(this) || LocalAdb.portReachable) {
                                stepAction.run();
                            } else {
                                new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                        .setTitle(tr("Turn On ADB Debugging"))
                                        .setMessage(tr("Please enable USB Debugging / Network Debugging in Developer Options, then return here to run this step."))
                                        .setPositiveButton(tr("Open Developer Options…"), (d2, w2) -> {
                                            Intent dev = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                                            if (!Suggestions.start(this, dev)) {
                                                Intent about = new Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS);
                                                if (!Suggestions.start(this, about)) {
                                                    Suggestions.start(this, new Intent(android.provider.Settings.ACTION_SETTINGS));
                                                }
                                            }
                                        })
                                        .setNegativeButton(tr("Skip This Step"), null)
                                        .show();
                            }
                        }));
                    })
                    .setNegativeButton(tr("Skip This Step"), null)
                    .show();
            return;
        }
        stepAction.run();
    }

    private void proceedToEasyAdbOrDirections() {
        if (!LocalAdb.isAdbSettingEnabled(this)) {
            permMode = 1;
            show(5, 1);
        } else {
            runEasyAdbGrant();
        }
    }

    private void runEasyAdbGrant() {
        final boolean closeAfter = LocalAdb.getAdbMode(this) == LocalAdb.ADB_MODE_SECURE_CLOSE;
        android.app.AlertDialog progress = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(closeAfter
                        ? tr("Secure Setup (ADB)")
                        : tr("Persistent ADB Setup"))
                .setMessage(tr("Granting permissions via local ADB\u2026\n\nIf an \u201cAllow USB debugging?\u201d prompt appears on screen, check \u201cAlways allow\u201d and press Allow."))
                .setCancelable(false)
                .create();
        progress.show();

        LocalAdb.grantAllPermissions(this, closeAfter, (success, msg) -> runOnUiThread(() -> {
            if (isFinishing()) return;
            try {
                progress.dismiss();
            } catch (Exception ignored) {
            }
            if (success) {
                if (closeAfter) {
                    new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(tr("Permissions Granted & ADB Closed"))
                            .setMessage((tr("WRITE_SECURE_SETTINGS and all launcher permissions are now active, and ADB (port 5555) has been closed for your security.\n\n")
                                    + tr("Tip: For maximum security, you can also select \u201cRevoke USB debugging authorizations\u201d in Developer Options.")))
                            .setPositiveButton(tr("Continue"), (d, w) -> next())
                            .setOnCancelListener(d -> next())
                            .show();
                } else {
                    android.widget.Toast.makeText(this,
                            tr("All permissions granted automatically!"),
                            android.widget.Toast.LENGTH_SHORT).show();
                    next();
                }
            } else {
                permMode = 1;
                show(5, 0);
                new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(tr("ADB Debugging Needed"))
                        .setMessage(tr("Could not connect to local ADB on port 5555.\n\nPlease make sure USB Debugging / Network Debugging is turned ON in Developer Options, then try again — or choose Manual Setup."))
                        .setPositiveButton(tr("OK"), null)
                        .setNeutralButton(tr("Manual Setup"), (d, w) -> {
                            permMode = 2;
                            show(5, 1);
                        })
                        .show();
            }
        }));
    }

    /** Step right after permissions: let the user add remote buttons and assign 1x, 2x, 3x tap actions. */
    void buttonMapperSetup() {
        title(tr("Button Mapper"));
        TextView s = subtitle(tr("Map any button on your remote with Single Tap (1x), Double Tap (2x) and Triple Tap (3x) actions — open the Control Panel, Recent Apps Switcher, or any app."));
        ((LinearLayout.LayoutParams) s.getLayoutParams()).width = u(580);
        ((LinearLayout.LayoutParams) s.getLayoutParams()).bottomMargin = u(16);

        LinearLayout l = scrollableList();
        if (!ButtonMapper.isServiceEnabled(this)) {
            option(l, tr("Enable Button Mapper in Accessibility…"),
                    tr("Required so mapped buttons work over every app"),
                    false,
                    () -> {
                        Intent acc = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        if (!Suggestions.start(this, acc)) {
                            Suggestions.start(this, new Intent(android.provider.Settings.ACTION_SETTINGS));
                        }
                    });
        }

        option(l, tr("+ Add New Button to Remap…"),
                tr("Assign Single Tap (1x), Double Tap (2x) & Triple Tap (3x) actions"),
                false,
                () -> ButtonMapper.showCaptureAndConfigureDialog(this, () -> show(index, 0)));

        List<Integer> mappedKeys = ButtonMapper.getMappedKeys(this);
        for (final int keyCode : mappedKeys) {
            option(l, ButtonMapper.keyLabel(this, keyCode),
                    ButtonMapper.keySummaryLabel(this, keyCode),
                    true,
                    () -> ButtonMapper.showConfigureKeyDialog(this, keyCode, () -> show(index, 0)));
        }

        option(l, tr("Continue"),
                mappedKeys.isEmpty() ? tr("You can also configure buttons later in Settings") : null,
                false,
                this::next);
        dots();
    }

    void finish_() {
        backdrop.animate().alpha(0f).setDuration(300).start();
        View top = new View(this);
        page.addView(top, new LinearLayout.LayoutParams(1, 0, 1f));
        int cs = u(64);
        Bitmap cb = Bitmap.createBitmap(cs, cs, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cc = new android.graphics.Canvas(cb);
        android.graphics.Paint cp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        cp.setColor(0xFF30D158);
        cc.drawCircle(cs / 2f, cs / 2f, cs / 2f, cp);
        cp.setColor(Color.WHITE);
        cp.setStyle(android.graphics.Paint.Style.STROKE);
        cp.setStrokeWidth(cs * 0.09f);
        cp.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        cp.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        android.graphics.Path tick = new android.graphics.Path();
        tick.moveTo(cs * 0.29f, cs * 0.52f);
        tick.lineTo(cs * 0.44f, cs * 0.67f);
        tick.lineTo(cs * 0.72f, cs * 0.37f);
        cc.drawPath(tick, cp);
        ImageView check = new ImageView(this);
        check.setImageBitmap(cb);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(u(64), u(64));
        clp.bottomMargin = u(18);
        page.addView(check, clp);
        title(tr("You're All Set"));
        subtitle(tr("Change any of this later in Settings, from the gear in Control Center."));
        LinearLayout l = list();
        option(l, tr("Go to Home Screen"), null, false, () -> {
            getSharedPreferences("setup", MODE_PRIVATE).edit().putBoolean(K_DONE, true).putInt("cur_step", 0).apply();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });
        View bottom = new View(this);
        page.addView(bottom, new LinearLayout.LayoutParams(1, 0, 1.4f));
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent ev) {
        MainActivity.navSound(this, ev);
        return super.dispatchKeyEvent(ev);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
