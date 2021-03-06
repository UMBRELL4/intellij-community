// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.ui;

import com.intellij.openapi.util.IconLoader.CachedImageIcon;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BareTestFixtureTestCase;
import com.intellij.ui.*;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.JBUIScale.ScaleContext;
import com.intellij.util.ui.JBUIScale.ScaleContextAware;
import com.intellij.util.ui.JBUIScale.UserScaleContext;
import com.intellij.util.ui.paint.ImageComparator;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.ExternalResource;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.MalformedURLException;

import static com.intellij.util.ui.JBUIScale.DerivedScaleType.DEV_SCALE;
import static com.intellij.util.ui.JBUIScale.DerivedScaleType.EFF_USR_SCALE;
import static com.intellij.util.ui.JBUIScale.ScaleType.*;
import static com.intellij.util.ui.TestScaleHelper.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

/**
 * Tests that {@link com.intellij.openapi.util.ScalableIcon#scale(float)} works correctly for custom JB icons.
 *
 * @author tav
 */
public class IconScaleTest extends BareTestFixtureTestCase {
  private static final int ICON_BASE_SIZE = 16;
  private static final float ICON_OBJ_SCALE = 1.75f;
  private static final float ICON_OVER_USR_SCALE = 1.0f;

  @ClassRule
  public static final ExternalResource manageState = new RestoreScaleRule();

  @BeforeClass
  public static void beforeClass() {
  }

  @Test
  public void test() throws MalformedURLException {
    // 0.75 is impractical system scale factor, however it's used to stress-test the scale subsystem.
    final double[] SCALES = {0.75f, 1, 2, 2.5f};

    //
    // 1) JRE-HiDPI
    //
    overrideJreHiDPIEnabled(true);
    if (SystemInfo.IS_AT_LEAST_JAVA9 || !SystemInfo.isLinux) {
      for (double s : SCALES) test(1, s);
    }

    //
    // 2) IDE-HiDPI
    //
    overrideJreHiDPIEnabled(false);
    for (double s : SCALES) test(s, s); // the system scale repeats the default user scale in IDE-HiDPI
  }

  public void test(double usrScale, double sysScale) throws MalformedURLException {
    JBUI.setUserScaleFactor((float)usrScale);
    JBUI.setSystemScaleFactor((float)sysScale);

    ScaleContext ctx = ScaleContext.create(SYS_SCALE.of(sysScale), USR_SCALE.of(usrScale));

    //
    // 1. CachedImageIcon
    //
    test(new CachedImageIcon(new File(getIconPath()).toURI().toURL()), ctx.copy());

    //
    // 2. DeferredIcon
    //
    CachedImageIcon icon = new CachedImageIcon(new File(getIconPath()).toURI().toURL());
    test(new DeferredIconImpl<>(icon, new Object(), false, o -> icon), UserScaleContext.create(ctx));

    //
    // 3. LayeredIcon
    //
    test(new LayeredIcon(new CachedImageIcon(new File(getIconPath()).toURI().toURL())), UserScaleContext.create(ctx));

    //
    // 4. RowIcon
    //
    test(new RowIcon(new CachedImageIcon(new File(getIconPath()).toURI().toURL())), UserScaleContext.create(ctx));
  }

  private static void test(Icon icon, UserScaleContext iconCtx) {
    ((ScaleContextAware)icon).updateScaleContext(iconCtx);

    ScaleContext ctx = ScaleContext.create(iconCtx);

    /*
     * (A) normal conditions
     */

    //noinspection UnnecessaryLocalVariable
    Icon iconA = icon;
    double usrSize2D = ctx.apply(ICON_BASE_SIZE, EFF_USR_SCALE);
    int usrSize = (int)Math.round(usrSize2D);
    int devSize = (int)Math.round(ctx.apply(usrSize2D, DEV_SCALE));

    assertIcon(iconA, iconCtx, usrSize, devSize);

    /*
     * (B) override scale
     */
    if (!(icon instanceof RetrievableIcon)) { // RetrievableIcon may return a copy of its wrapped icon and we may fail to override scale in the origin.

      Icon iconB = IconUtil.overrideScale(IconUtil.deepCopy(icon, null), USR_SCALE.of(ICON_OVER_USR_SCALE));

      usrSize2D = ICON_BASE_SIZE * ICON_OVER_USR_SCALE * ctx.getScale(OBJ_SCALE);
      usrSize = (int)Math.round(usrSize2D);
      devSize = (int)Math.round(ctx.apply(usrSize2D, DEV_SCALE));

      assertIcon(iconB, iconCtx, usrSize, devSize);
    }

    /*
     * (C) scale icon
     */
    Icon iconC = IconUtil.scale(icon, null, ICON_OBJ_SCALE);

    assertNotSame("scaled instance of the icon " + iconCtx, icon, iconC);
    assertEquals("ScaleContext of the original icon changed " + iconCtx, iconCtx, ((ScaleContextAware)icon).getScaleContext());

    usrSize2D = ctx.apply(ICON_BASE_SIZE, EFF_USR_SCALE);
    double scaledUsrSize2D = usrSize2D * ICON_OBJ_SCALE;
    int scaledUsrSize = (int)Math.round(scaledUsrSize2D);
    int scaledDevSize = (int)Math.round(ctx.apply(scaledUsrSize2D, DEV_SCALE));

    assertIcon(iconC, iconCtx, scaledUsrSize, scaledDevSize);

    // Additionally check that the original image hasn't changed after scaling
    Pair<BufferedImage, Graphics2D> pair = createImageAndGraphics(ctx.getScale(DEV_SCALE), icon.getIconWidth(), icon.getIconHeight());
    BufferedImage iconImage = pair.first;
    Graphics2D g2d = pair.second;

    icon.paintIcon(null, g2d, 0, 0);

    BufferedImage goldImage = loadImage(getIconPath(), ctx);

    ImageComparator.compareAndAssert(
      new ImageComparator.AASmootherComparator(0.1, 0.1, new Color(0, 0, 0, 0)), goldImage, iconImage, null);
  }

  static void assertIcon(Icon icon, UserScaleContext iconCtx, int usrSize, int devSize) {
    ScaleContext ctx = ScaleContext.create(iconCtx);
    assertEquals("unexpected icon user width (ctx: " + iconCtx + ")", usrSize, icon.getIconWidth());
    assertEquals("unexpected icon user height (ctx: " + iconCtx + ")", usrSize, icon.getIconHeight());
    assertEquals("unexpected icon real width (ctx: " + iconCtx + ")", devSize, ImageUtil.getRealWidth(IconUtil.toImage(icon, ctx)));
    assertEquals("unexpected icon real height (ctx: " + iconCtx + ")", devSize, ImageUtil.getRealHeight(IconUtil.toImage(icon, ctx)));
  }

  private static String getIconPath() {
    return PlatformTestUtil.getPlatformTestDataPath() + "ui/abstractClass.svg";
  }
}
