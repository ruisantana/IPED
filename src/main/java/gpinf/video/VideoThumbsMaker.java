/*
 * Copyright 2015-2015, Wladimir Leite
 * 
 * This file is part of Indexador e Processador de Evidencias Digitais (IPED).
 *
 * IPED is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * IPED is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IPED.  If not, see <http://www.gnu.org/licenses/>.
 */
package gpinf.video;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Classe principal de geração de imagens com cenas extraídas de vídeos.
 * Utiliza o MPlayer para realização da extração de frames e na sequência
 * monta uma imagem única no layout especificado de linhas e colunas. 
 * @author Wladimir Leite
 */
public class VideoThumbsMaker {
    private String mplayer = "mplayer.exe";
    private int timeoutProcess = 15000;
    private int timeoutInfo = 10000;
    private int timeoutFirstCall = 180000;
    private boolean verbose = false;
    private int quality = 50;
    private boolean checkContent = false;
    private String escape = null;
    private boolean firstCall = true;
    private boolean isWindows = false;
    private static final String prefix = "_vtm";
    private int ignoreWaitKeyFrame;
    private int maxLines = 20000;

    public String getVersion() {
        List<String> cmds = new ArrayList<String>(Arrays.asList(new String[] {mplayer}));
        String info = run(cmds.toArray(new String[0]), firstCall ? timeoutFirstCall : timeoutInfo);
        if (info != null) {
            if (info.indexOf("\n") > 0) {
                info = info.substring(0, info.indexOf("\n"));
            }
        }
        return info;
    }

    public VideoProcessResult getInfo(File inOrg, File tmp) throws Exception {
        return createThumbs(inOrg, tmp, null);
    }

    public VideoProcessResult createThumbs(File inOrg, File tmp, List<VideoThumbsOutputConfig> outs) throws Exception {
        if (escape == null) {
            try {
                escape = "";
                if (System.getProperty("os.name").toLowerCase().indexOf("win") >= 0) {
                    isWindows = true;
                    escape = "\\\"";
                }
            } catch (Exception e) {}
        }

        long start = System.currentTimeMillis();
        VideoProcessResult result = new VideoProcessResult();

        File in = inOrg;
        List<String> cmds = new ArrayList<String>(Arrays.asList(new String[] {mplayer,"-noautosub","-noconsolecontrols","-vo","null","-ao","null","-frames","0","-identify",in.getPath()}));

        File subTmp = new File(tmp, prefix + Thread.currentThread().getId() + "_" + System.currentTimeMillis());
        subTmp.mkdir();
        subTmp.deleteOnExit();

        boolean fixed = false;
        File lnk = null;
        for (int step = 0; step <= 1; step++) {
            String info = run(cmds.toArray(new String[0]), firstCall ? timeoutFirstCall : timeoutInfo);
            if (firstCall) {
                firstCall = false;
                maxLines = 2000;
            }

            if (step == 0 && info.indexOf("File not found") >= 0 && !fixed) {
                fixed = true;
                String shortName = getShortName(inOrg);
                if (shortName != null) {
                    if (verbose) System.err.println("Usando nome curto = " + shortName);
                    in = new File(inOrg.getParentFile(), shortName);
                    cmds.set(cmds.size() - 1, in.getPath());
                    step--;
                    continue;
                }
                lnk = makeLink(inOrg, subTmp);
                if (lnk != null) {
                    if (verbose) System.err.println("Usando link = " + lnk);
                    in = lnk;
                    cmds.set(cmds.size() - 1, in.getPath());
                    step--;
                    continue;
                }
            }

            long duration = getDuration(info);
            result.setVideoDuration(duration);

            Dimension dimension = getDimension(info);
            result.setDimension(dimension);

            if (result.getVideoDuration() != 0 && result.getDimension() != null) break;

            cmds.add(1, "-demuxer");
            cmds.add(2, "lavf");
        }
        if (outs == null) {
            result.setFile(in);
            result.setSubTemp(subTmp);
            return result;
        }

        if (result.getVideoDuration() == 0 || result.getDimension() == null || result.getDimension().width == 0 || result.getDimension().height == 0) {
            cleanTemp(subTmp);
            return result;
        }

        if (verbose) {
            System.err.println("DURATION: " + result.getVideoDuration());
        }

        int maxThumbs = 0;
        int maxWidth = 0;
        for (VideoThumbsOutputConfig config : outs) {
            int curr = config.getColumns() * config.getRows();
            if (maxThumbs < curr) maxThumbs = curr;
            if (maxWidth < config.getThumbWidth()) maxWidth = config.getThumbWidth();
        }
        int frequency = (int) ((result.getVideoDuration() - 1) * 0.001 / (maxThumbs + 2));
        if (frequency < 1) frequency = 1;

        String s1 = "VO: [jpeg] ";
        String s2 = " => ";
        File[] files = null;
        int pos = 0;

        int maxHeight = result.getDimension().height * maxWidth / result.getDimension().width;
        String scale = "scale=" + maxWidth + ":" + maxHeight;

        boolean scaled = result.getDimension().width > maxWidth;
        cmds = new ArrayList<String>();
        cmds.add(mplayer);
        cmds.add("-noconsolecontrols");
        cmds.add("-noautosub");
        if (ignoreWaitKeyFrame != 1) {
            cmds.add("-lavdopts");
            cmds.add("wait_keyframe");
        }
        if (scaled) cmds.addAll(Arrays.asList(new String[] {"-sws","0","-vf",scale}));

        String ssVal = String.valueOf(result.getVideoDuration() < 5000 ? 0 : Math.max(frequency / 2, 1));
        cmds.addAll(Arrays.asList(new String[] {"-vo","jpeg:smooth=50:nobaseline:quality=" + quality + ":outdir=" + escape + subTmp.getPath().replace('\\', '/') + escape,"-ao","null","-ss",ssVal,"-sstep",String.valueOf(frequency),"-frames",String.valueOf(maxThumbs + 1),in.getPath()}));

        for (int step = 0; step <= 2; step++) {
            String ret = run(cmds.toArray(new String[0]), timeoutProcess);
            files = subTmp.listFiles(new FileFilter() {
                public boolean accept(File pathname) {
                    return pathname.getName().toLowerCase().endsWith(".jpg");
                }
            });
            if (!scaled) {
                int p1 = ret.indexOf(s1);
                if (p1 > 0) {
                    int p2 = ret.indexOf(s2, p1);
                    if (p2 > 0) {
                        int p3 = ret.indexOf(" ", p2 + s2.length());
                        if (p3 > 0) {
                            String[] s = ret.substring(p1 + s1.length(), p3).split(s2);
                            if (s.length == 2) {
                                s = s[1].split("x");
                                scaled = true;
                                Dimension nd = new Dimension(Integer.parseInt(s[0]), Integer.parseInt(s[1]));
                                if (!nd.equals(result.getDimension())) {
                                    result.setDimension(nd);
                                    pos = cmds.indexOf(scale);
                                    if (pos >= 0) {
                                        maxHeight = result.getDimension().height * maxWidth / result.getDimension().width;
                                        scale = "scale=" + maxWidth + ":" + maxHeight;
                                        cmds.set(pos, scale);
                                        step--;
                                        continue;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (ignoreWaitKeyFrame == 0 && step == 0) {
                String rlc = ret.toLowerCase();
                if ((rlc.indexOf("unknown") >= 0 || rlc.indexOf("suboption") >= 0 || rlc.indexOf("error") >= 0) && (rlc.indexOf("lavdopts") >= 0 || rlc.indexOf("wait_keyframe") >= 0)) {
                    step = -1;
                    ignoreWaitKeyFrame = 1;
                    cmds.remove(4);
                    cmds.remove(3);
                    System.err.println(">>>>> OPCAO '-lavdopts wait_keyframe' DESABILITADA.");
                    continue;
                }
            }

            if (files.length > (maxThumbs - 1) / 3 && ret.indexOf("Error while decoding frame") < 0) break;
            if (step == 1) {
                pos = cmds.indexOf("-sstep");
                cmds.remove(pos + 1);
                cmds.remove(pos);
            } else if (step == 2) {
                cmds.add(pos + 2, "-sstep");
                cmds.add(pos + 3, String.valueOf(frequency));
                cmds.add(1, "-demuxer");
                cmds.add(2, "lavf");
            }
        }
        if (ignoreWaitKeyFrame == 0) ignoreWaitKeyFrame = -1;

        int[] rgbPrev = null;
        Arrays.sort(files);
        List<File> images = new ArrayList<File>();
        for (File file : files) {
            file.deleteOnExit();
            if (checkContent) {
                BufferedImage img = ImageIO.read(file);
                boolean empty = true;
                int[] rgb = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
                int base = rgb[img.getHeight() / 2 * img.getWidth() + img.getWidth() / 2];
                int cntm = 0;
                for (int i = 0; i < rgb.length; i += 4) {
                    if (rgbDist(rgb[i], base) > 80) {
                        cntm++;
                        if (cntm > rgb.length / 50 + 1) {
                            empty = false;
                            break;
                        }
                    }
                }
                boolean repeated = false;
                if (!empty) {
                    if (rgbPrev != null) {
                        repeated = rgb.length == rgbPrev.length;
                        if (repeated) {
                            for (int i = 0; i < rgb.length; i += 4) {
                                if (rgb[i] != rgbPrev[i]) {
                                    repeated = false;
                                    break;
                                }
                            }
                        }
                    }
                    if (!repeated) rgbPrev = rgb;
                }
                if (!empty && !repeated) images.add(file);
            } else {
                images.add(file);
            }
        }
        if (lnk != null) lnk.delete();
        if (images.size() == 0) {
            cleanTemp(subTmp);
            return result;
        }
        for (VideoThumbsOutputConfig config : outs) {
            generateGridImage(config, images, result.getDimension());
        }
        cleanTemp(subTmp);

        result.setSuccess(true);
        result.setProcessingTime(System.currentTimeMillis() - start);
        return result;
    }

    private File makeLink(File in, File dir) {
        try {
            String ext = getExtensao(in.getName());
            if (ext == null) ext = "";
            File lnk = new File(dir, "vtc" + System.currentTimeMillis() + ext);
            lnk.deleteOnExit();
            Path link = Files.createSymbolicLink(lnk.toPath(), in.toPath());
            return link.toFile();
        } catch (Exception e) {}
        return null;
    }

    private String getShortName(File in) {
        if (!isWindows) return null;
        String[] cdir = new String[] {"cmd","/c","dir","/x",in.getPath()};
        String sdir = run(cdir, 1000);
        if (sdir != null) {
            String ext = getExtensao(in.getName());
            if (ext != null) {
                String[] lines = sdir.split("\n");
                for (String line : lines) {
                    if (line.endsWith(ext)) {
                        String[] s = line.split(" +");
                        if (s.length > 4) {
                            String shortName = s[3];
                            if (shortName.indexOf('~') > 0) {
                                return shortName;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static final int rgbDist(int a, int b) {
        return Math.abs(s(a, 16) - s(b, 16)) + Math.abs(s(a, 8) - s(b, 8)) + Math.abs(s(a, 0) - s(b, 0));
    }

    private static final int s(int a, int n) {
        return (a >>> n) & 0xFF;
    }

    private void generateGridImage(VideoThumbsOutputConfig config, List<File> images, Dimension dimension) throws IOException {
        int w = config.getThumbWidth();
        double rate = images.size() * 0.999 / (config.getRows() * config.getColumns());
        int h = dimension.height * w / dimension.width;
        int border = config.getBorder();

        BufferedImage img = new BufferedImage(2 + config.getColumns() * (w + border) + border, 2 + config.getRows() * (h + border) + border, BufferedImage.TYPE_INT_BGR);
        Graphics2D g2 = (Graphics2D) img.getGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g2.setColor(new Color(222, 222, 222));
        g2.fillRect(0, 0, img.getWidth(), img.getHeight());
        g2.setColor(new Color(22, 22, 22));
        g2.drawRect(0, 0, img.getWidth() - 1, img.getHeight() - 1);

        double pos = rate * 0.4;
        for (int i = 0; i < config.getRows(); i++) {
            int y = 1 + i * (h + border) + border;
            for (int j = 0; j < config.getColumns(); j++) {
                int x = 1 + j * (w + border) + border;
                BufferedImage in = ImageIO.read(images.get(Math.min(images.size() - 1, (int) pos)));
                g2.drawImage(in, x, y, w, h, null);
                pos += rate;
            }
        }
        g2.dispose();
        ImageIO.write(img, "jpeg", config.getOutFile());
    }

    private void cleanTemp(File subTmp) {
        File[] files = subTmp.listFiles();
        for (File file : files) {
            file.delete();
        }
        subTmp.delete();
    }

    private long getDuration(String info) throws Exception {
        String s1 = "ID_LENGTH=";
        int p1 = info.indexOf(s1);
        if (p1 < 0) return -1;
        int p2 = info.indexOf('\n', p1);
        String s = info.substring(p1 + s1.length(), p2);
        return (long) (1000 * Double.parseDouble(s));
    }

    private Dimension getDimension(String info) throws Exception {
        String s1 = "ID_VIDEO_WIDTH=";
        int p1 = info.indexOf(s1);
        if (p1 < 0) return null;
        int p2 = info.indexOf('\n', p1);
        if (p2 < 0) return null;
        String s3 = "ID_VIDEO_HEIGHT=";
        int p3 = info.indexOf(s3);
        if (p3 < 0) return null;
        int p4 = info.indexOf('\n', p3);
        if (p4 < 0) return null;
        return new Dimension(Integer.parseInt(info.substring(p1 + s1.length(), p2)), Integer.parseInt(info.substring(p3 + s3.length(), p4)));
    }

    private final String run(String[] cmds, int timeout) {
        if (verbose) {
            System.err.print("CMD = ");
            for (int i = 0; i < cmds.length; i++) {
                System.err.print(cmds[i] + " ");
            }
            System.err.println();
            System.err.print("TIMEOUT = " + timeout);
            System.err.println();
        }

        StringBuilder sb = new StringBuilder();
        Counter counter = new Counter();
        try {
            final Process process = Runtime.getRuntime().exec(cmds);

            StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), sb, counter, process);
            StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), sb, counter, process);

            errorGobbler.start();
            outputGobbler.start();

            long t = System.currentTimeMillis() + timeout;
            while (true) {
                Thread.sleep(10);
                try {
                    process.exitValue();
                    break;
                } catch (IllegalThreadStateException threadStateException) {
                    if (System.currentTimeMillis() > t) {
                        if (verbose) System.err.println("TIMEOUT");
                        process.destroy();
                        break;
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            if (verbose) {
                System.err.print("Erro executando comando '");
                e.printStackTrace();
            }
        }
        return null;
    }

    private String getExtensao(String nome) {
        int pos = nome.lastIndexOf('.');
        if (pos >= 0) return nome.substring(pos + 1);
        return null;
    }

    public void setMplayer(String mplayer) {
        this.mplayer = mplayer;
    }

    public void setTimeoutProcess(int timeoutProcess) {
        this.timeoutProcess = timeoutProcess;
    }

    public void setTimeoutInfo(int timeoutInfo) {
        this.timeoutInfo = timeoutInfo;
    }

    public void setTimeoutFirstCall(int timeoutFirstCall) {
        this.timeoutFirstCall = timeoutFirstCall;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    class Counter {
        int val;

        synchronized void inc() {
            val++;
        }
    }

    class StreamGobbler extends Thread {
        InputStream is;
        StringBuilder sb;
        Counter counter;
        Process process;

        StreamGobbler(InputStream is, StringBuilder sb, Counter counter, Process process) {
            this.is = is;
            this.sb = sb;
            this.counter = counter;
            this.process = process;
        }

        public void run() {
            try {
                InputStreamReader isr = new InputStreamReader(is);
                BufferedReader br = new BufferedReader(isr);
                String line = null;
                while ((line = br.readLine()) != null) {
                    synchronized (sb) {
                        counter.inc();
                        sb.append(line).append('\n');
                        if (verbose) System.err.println(line);
                        if (line.indexOf("Error while decoding frame!") >= 0 || counter.val > maxLines) {
                            process.destroy();
                        }
                    }
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }
    }
}
