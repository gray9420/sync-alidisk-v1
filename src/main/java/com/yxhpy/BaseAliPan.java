package com.yxhpy;

import cn.hutool.core.codec.Base64Encoder;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.*;
import cn.hutool.json.JSONUtil;
import cn.hutool.log.GlobalLogFactory;
import cn.hutool.log.Log;
import com.google.zxing.common.StringUtils;
import com.yxhpy.conifg.RequestConfig;
import com.yxhpy.entity.DownloadTempInfo;
import com.yxhpy.fileWatch.FileListener;
import com.yxhpy.entity.DownloadEntity;
import com.yxhpy.entity.EncFileInfoEntity;
import com.yxhpy.entity.response.*;
import com.yxhpy.enumCode.QrStatus;
import com.yxhpy.utils.*;
import okhttp3.*;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author liuguohao
 */
public class BaseAliPan {
    private final Request request;
    private PdsLoginResult loginInfo;
    private String remoteFileId;
    private static final String R_CODE = "code=(\\w+)";
    private static String BASE_PATH;
    public static String REMOTE_PATH;
    public static final Integer PART_SIZE;
    private static final String FILE_TYPE_FILE = "file";
    private static final String FILE_TYPE_FOLDER = "folder";
    private static final ConcurrentSkipListSet<String> EXISTS_FILE_NAMES = new ConcurrentSkipListSet<>();

    private final Log log = GlobalLogFactory.get().createLog(BaseAliPan.class);

    static {
        BASE_PATH = RequestConfig.BASE_PATH;
        REMOTE_PATH = RequestConfig.REMOTE_PATH;
        PART_SIZE = RequestConfig.PART_SIZE;
    }

    public BaseAliPan() {
        request = new Request();
    }

    public DataEntity getLoginQr() {
        request.get("https://auth.aliyundrive.com/v2/oauth/authorize?client_id=25dzX3vbYqktVxyX&redirect_uri=https%3A%2F%2Fwww.aliyundrive.com%2Fsign%2Fcallback&response_type=code&login_type=custom&state=%7B%22origin%22%3A%22https%3A%2F%2Fwww.aliyundrive.com%22%7D");
        HttpResponse response = request.get("https://passport.aliyundrive.com/newlogin/qrcode/generate.do?appName=aliyun_drive&fromSite=52&appName=aliyun_drive&appEntrance=web&_csrf_token=8iPG8rL8zndjoUQhrQnko5&umidToken=27f197668ac305a0a521e32152af7bafdb0ebc6c&isMobile=false&lang=zh_CN&returnUrl=&hsiz=1d3d27ee188453669e48ee140ea0d8e1&fromSite=52&bizParams=&_bx-v=2.0.31");
        ResultEntity resultEntity = JsonUtils.responseToBean(response, ResultEntity.class);
        if (!AnalyticalResults.isSuccess(resultEntity)) {
            log.error("获取二维码失败");
            return null;
        }
        return resultEntity.getContent().getData();
    }

    public ResultEntity getQrStatus(HashMap<String, Object> map) {
        while (true) {
            HttpResponse response = request.post("https://passport.aliyundrive.com/newlogin/qrcode/query.do?appName=aliyun_drive&fromSite=52&_bx-v=2.0.31", map);
            ResultEntity resultEntity = JsonUtils.responseToBean(response, ResultEntity.class);
            if (!AnalyticalResults.isSuccess(resultEntity)) {
                log.error("二维码状态获取失败");
                break;
            }
            String qrCodeStatus = resultEntity.getContent().getData().getQrCodeStatus();
            QrStatus qrStatus = QrStatus.getQrStatusByName(qrCodeStatus);
            if (qrStatus == QrStatus.CONFIRMED || qrStatus == QrStatus.EXPIRED) {
                return resultEntity;
            }
            assert qrStatus != null;
            log.info(qrStatus.getDes());
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public String getRefreshToken(String accessToken) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("token", accessToken);
        HttpResponse response = request.postJson("https://auth.aliyundrive.com/v2/oauth/token_login", map);
        Pattern compile = Pattern.compile(R_CODE);
        Matcher matcher = compile.matcher(response.body());
        if (matcher.find()) {
            String code = matcher.group(1);
            map.clear();
            map.put("code", code);
            response = request.postJson("https://api.aliyundrive.com/token/get", map);
            PdsLoginResult pdsLoginResult = JsonUtils.responseToBean(response, PdsLoginResult.class);
            return pdsLoginResult.getRefreshToken();
        }
        return null;
    }

    public boolean refreshToken(String refreshToken) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("refresh_token", refreshToken);
        Request request = new Request();
        HttpResponse response = request.postJson("https://api.aliyundrive.com/token/refresh", map);
        PdsLoginResult loginEntity = JsonUtils.responseToBean(response, PdsLoginResult.class);
        if (loginEntity == null || loginEntity.getTokenType() == null || loginEntity.getAccessToken() == null) {
            log.warn("token已经过期，请重新登录:" + response.body());
            return false;
        }
        loginInfo = loginEntity;
        this.request.getHttpRequest().bearerAuth(loginEntity.getAccessToken());
        log.info("免密登录成功，无需重新扫码");
        return true;
    }

    public FolderEntity getFolderFileList(String parentFileId) {
        String defaultDriveId = loginInfo.getDefaultDriveId();
        HashMap<String, Object> map = new HashMap<>();
        map.put("all", false);
        map.put("drive_id", defaultDriveId);
        map.put("fields", "*");
        map.put("image_thumbnail_process", "image/resize,w_256/format,jpeg");
        map.put("image_url_process", "image/resize,w_1920/format,jpeg/interlace,1");
        map.put("limit", 20);
        map.put("order_by", "updated_at");
        map.put("order_direction", "DESC");
        map.put("parent_file_id", parentFileId);
        map.put("url_expire_sec", 14400);
        map.put("video_thumbnail_process", "video/snapshot,t_1000,f_jpg,ar_auto,w_256");
        while (true) {
            Map<String, String> headers = request.getHeaders();
            for (String s : headers.keySet()) {
                System.out.println(s +":"+headers.get(s));
            }
            HttpRequest httpRequest = request.getHttpRequest();
            System.out.println(JSONUtil.toJsonStr(httpRequest));
            HttpResponse response = request.postJson("https://api.aliyundrive.com/adrive/v3/file/list", map);
            if (response.getStatus() == 200) {
                return JsonUtils.responseToBean(response, FolderEntity.class);
            }
            // 防止429
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void trashFile(String fileId) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("drive_id", loginInfo.getDefaultDriveId());
        map.put("file_id", fileId);
        HttpResponse response = request.postJson("https://api.aliyundrive.com/v2/recyclebin/trash", map);
        log.info("删除文件：" + response.body());
    }

    public EncFileInfoEntity getFileInfo(String filePath) {
        File file = new File(filePath);
        String name = FileUtil.getName(file);
        long size = FileUtil.size(file);
        String accessToken = loginInfo.getAccessToken();
        String limit = MD5Util.hashCode(accessToken.getBytes(), MD5Util.MD5);
        BigInteger bigInteger = new BigInteger(limit.substring(0, 16), 16);
        long left = size == 0 ? size : bigInteger.mod(BigInteger.valueOf(size)).longValueExact();
        long right = Math.min(left + 8, size);
        int preSize = (int) Math.min(1024, size);
        try (FileInputStream stream = new FileInputStream(file)) {
            String contentHash = MD5Util.hashCode(stream, MD5Util.SHA1);

            byte[] preLimit = new byte[preSize];
            stream.getChannel().position(0);
            stream.read(preLimit);
            String preHash = MD5Util.hashCode(preLimit, MD5Util.SHA1);

            stream.getChannel().position(left);
            byte[] proofCodeLimit = new byte[(int)(right - left)];
            stream.read(proofCodeLimit);
            String proofCode = Base64Encoder.encode(proofCodeLimit);
            return new EncFileInfoEntity(name, size, preHash, contentHash, proofCode);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public FileExistsEntity fileExists(String parentFileId, EncFileInfoEntity fileInfo) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("drive_id", loginInfo.getDefaultDriveId());
        map.put("part_info_list", FileUtils.getFileParts(fileInfo.getFileSize(), PART_SIZE));
        map.put("parent_file_id", parentFileId);
        map.put("name", fileInfo.getFileName());
        map.put("type", "file");
        //        refuse auto_rename
        map.put("check_name_mode", "refuse");
        map.put("size", fileInfo.getFileSize());
        map.put("pre_hash", fileInfo.getPreHash());
        HttpResponse response = request.postJson("https://api.aliyundrive.com/adrive/v2/file/createWithFolders", map);
        return JsonUtils.responseToBean(response, FileExistsEntity.class);
    }

    public FileExistsEntity createFile(String parentFileId, EncFileInfoEntity fileInfo) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("drive_id", loginInfo.getDefaultDriveId());
        map.put("name", fileInfo.getFileName());
        map.put("parent_file_id", parentFileId);
        map.put("type", FILE_TYPE_FILE);
        map.put("part_info_list", FileUtils.getFileParts(fileInfo.getFileSize(), PART_SIZE));
        map.put("check_name_mode", "refuse");
        map.put("size", fileInfo.getFileSize());
        map.put("content_hash", fileInfo.getContentHash());
        map.put("content_hash_name", "sha1");
        map.put("proof_code", fileInfo.getProofCode());
        map.put("proof_version", "v1");
        HttpResponse response = request.postJson("https://api.aliyundrive.com/adrive/v2/file/createWithFolders", map);
        return JsonUtils.responseToBean(response, FileExistsEntity.class);
    }

    public FolderCreateEntity createWithFolders(String parentFileId, String name) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("drive_id", loginInfo.getDefaultDriveId());
        map.put("name", name);
        map.put("parent_file_id", parentFileId);
        map.put("type", FILE_TYPE_FOLDER);
        map.put("check_name_mode", "refuse");
        HttpResponse response = request.postJson("https://api.aliyundrive.com/adrive/v2/file/createWithFolders", map);
        return JsonUtils.responseToBean(response, FolderCreateEntity.class);
    }

    public void startLogin() {
        while (true) {
            DataEntity loginQr = this.getLoginQr();
            if (loginQr == null) {
                return;
            }
            QrUtils.dialogShowQrImg(loginQr.getCodeContent());
            HashMap<String, Object> map = new HashMap<>();
            map.put("ck", loginQr.getCk());
            map.put("t", loginQr.getT());
            ResultEntity resultEntity = this.getQrStatus(map);
            if (resultEntity == null) {
                return;
            }
            if (!AnalyticalResults.isSuccess(resultEntity)) {
                log.error("二维码状态获取失败");
                return;
            }
            String qrCodeStatus = resultEntity.getContent().getData().getQrCodeStatus();
            QrStatus qrStatus = QrStatus.getQrStatusByName(qrCodeStatus);
            if (qrStatus == QrStatus.EXPIRED) {
                log.info("二维码超时，重新获取二维码");
                continue;
            }
            if (qrStatus == QrStatus.CONFIRMED) {
                log.info("登录成功，登录类型：" + resultEntity.getContent().getData().getLoginType());
                String bizExt = resultEntity.getContent().getData().getBizExt();
                LoginEntity loginEntity = JsonUtils.base64ToBean(bizExt, LoginEntity.class);
                if (loginEntity == null) {
                    log.error("错误，获取登录信息失败");
                    return;
                }
                loginInfo = loginEntity.getPdsLoginResult();
                request.getHttpRequest().bearerAuth(loginInfo.getAccessToken());
                log.info("从登录获取的AccessToken获取RefreshToken");
                String refreshToken = getRefreshToken(loginInfo.getAccessToken());
                log.info("获取RefreshToken为：" + refreshToken);
                loginInfo.setRefreshToken(refreshToken);
                log.info("将RefreshToken保存到本地");
                JsonUtils.writeBean(loginInfo, "loginInfo");
                break;
            }
        }
    }

    public void downloadFile(String path, String name, String fileId) {
        File file = new File(path);
        if (!file.exists()) {
            if (file.mkdirs()) {
                log.info("文件夹 " + path + " 不存在自动创建成功");
            }
        }
        String fileName = path + File.separator + name;
        if (Objects.equals(getFileIdByPath(fileName), fileId)) {
            return;
        }
        addFileId(fileName, fileId);
        HashMap<String, Object> map = new HashMap<>();
        map.put("drive_id", loginInfo.getDefaultDriveId());
        map.put("file_id", fileId);
        HttpResponse response = request.postJson("https://api.aliyundrive.com/v2/file/get_download_url", map);
        FileDownloadEntity fileDownloadEntity = JsonUtils.responseToBean(response, FileDownloadEntity.class);
        log.info("获取文件成功：" + fileDownloadEntity);
		File targetFile = new File(fileName);
		if (targetFile.exists()) {
			// 目标文件已存在 校验文件摘要 文件不同才进行下载
			try (FileInputStream stream = new FileInputStream(targetFile)) {
				String contentHash = MD5Util.hashCode(stream,
						fileDownloadEntity.getContentHashName());
				if (contentHash.equalsIgnoreCase(
						fileDownloadEntity.getContentHash())) {
					log.info("{} no need update", fileName);
					return;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
        log.info("下载文件到" + fileName);
        String url = fileDownloadEntity.getUrl();
        if (RequestConfig.MULTI_THREAD){
			// 多线程下载 支持断点（占用磁盘大）
			// doDownload(fileName,fileDownloadEntity);
			// 多线程下载 支持断点（新方案不会占用多余空间）
			doDownload2(fileName, fileDownloadEntity);
        }else{
            //异步请求
            doDownload(fileName, url);
        }
    }

    private void doDownload(String fileName, String url) {
        try (FileOutputStream fileOutputStream = new FileOutputStream(fileName)) {
            ResponseBody body = Request.download(url, Headers.of("Referer", "https://www.aliyundrive.com/"));
            if (body != null) {
                byte[] bytes = body.bytes();
                SafeFile safeFile = SafeFile.getInstance();
                safeFile.handler(bytes, false);
                fileOutputStream.write(bytes);
            }
            log.info("下载完成" + fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    /**
     * 下载多线程方式 支持断点续传
     *
     * @return void
     * @since 2022年06月16日 14:51:13
     * @author zengzhixing
     */
    private void doDownload(String fileName,
                            FileDownloadEntity fileDownloadEntity) {
        ExecutorService service = Executors
                .newFixedThreadPool(RequestConfig.MULTI_THREAD_NUM == 0
                        ? Runtime.getRuntime().availableProcessors()
                        : RequestConfig.MULTI_THREAD_NUM);
        int downloadPartSize = RequestConfig.MULTI_SIZE;
        // 多线程分段下载
        try (FileOutputStream fileOutputStream = new FileOutputStream(
                fileName)) {
            int size = fileDownloadEntity.getSize();
            log.info("size = {}", size);
            int cnt = size % downloadPartSize == 0 ? size / downloadPartSize
                    : size / downloadPartSize + 1;
            List<File> files = new ArrayList<>();
            AtomicInteger finCnt = new AtomicInteger();
            for (int i = 0; i < cnt; i++) {
                File tempFile = new File(fileName + ".temp" + i);
                files.add(tempFile);
                int start = i * downloadPartSize;
                int end = Math.min(size - 1, (i + 1) * downloadPartSize - 1);
                if (tempFile.exists() && tempFile.length() == end - start + 1) {
                    log.info("{} has download", tempFile.getName());
                    finCnt.incrementAndGet();
                    continue;
                }
                service.submit(() -> {
                    int reTry = RequestConfig.MULTI_TRY;
                    while (reTry > 0) {
                        reTry--;
                        try (BufferedOutputStream bos = new BufferedOutputStream(
                                new FileOutputStream(tempFile))) {
                            log.info("start = {} end = {}", start, end);
                            ResponseBody body = Request.download(
                                    fileDownloadEntity.getUrl(),
                                    Headers.of("Referer",
                                            "https://www.aliyundrive.com/",
                                            "Range",
                                            "bytes=" + start + "-" + end));
                            if (body != null) {
                                byte[] bytes = body.bytes();
                                SafeFile safeFile = SafeFile.getInstance();
                                safeFile.handler(bytes, false);
                                bos.write(bytes);
                                bos.flush();
                            }
                            finCnt.incrementAndGet();
                            return;
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    log.info("{} try download fail", tempFile.getName());
                });
            }
            service.shutdown();
            while (!service.isTerminated()) {
                TimeUnit.SECONDS.sleep(2);
                log.info("download {} ......", fileName);
            }
            if (files.size() > finCnt.get()) {
                log.info("filesSize = {} finCnt = {}", files.size(),
                        finCnt.get());
                log.info("download {} fail", fileName);
            } else {
                log.info("merge start");
                mergeFile(fileOutputStream, files);
                log.info("下载完成" + fileName);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 合并下载的片段文件
     *
     * @return void
     * @since 2022年06月16日 14:51:40
     * @author zengzhixing
     */
    private void mergeFile(FileOutputStream fileOutputStream,
                           List<File> files) {
        try (BufferedOutputStream bos = new BufferedOutputStream(
                fileOutputStream)) {
            byte[] bytes = new byte[1024];
            for (File file : files) {
                FileInputStream in = new FileInputStream(file);
                int len = 0;
                while ((len = in.read(bytes)) != -1) {
                    bos.write(bytes, 0, len);
                }
                in.close();
            }
            files.forEach(File::delete);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 下载多线程方式 支持断点续传
     *
     * @return void
     * @since 2022年06月16日 14:51:13
     * @author zengzhixing
     */
	private void doDownload2(String fileName,
			FileDownloadEntity fileDownloadEntity) {
		ExecutorService service = Executors
				.newFixedThreadPool(RequestConfig.MULTI_THREAD_NUM == 0
						? Runtime.getRuntime().availableProcessors()
						: RequestConfig.MULTI_THREAD_NUM);
		int downloadPartSize = RequestConfig.MULTI_SIZE;
		// 多线程分段下载
		File downloading = new File(fileName + ".downloading");
		DownloadTempInfo tempInfo = null;
        ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
        WriteLock writeLock = rwLock.writeLock();
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(
				downloading, "rw")) {

			tempInfo = getDownloadTempInfo(randomAccessFile,fileDownloadEntity);
			if (tempInfo == null || tempInfo
					.checkFileInfo(fileDownloadEntity) == Boolean.FALSE) {
			    //没有信息或者信息检查不通过（文件已更改）
				tempInfo = new DownloadTempInfo(fileDownloadEntity,
						downloadPartSize);
				updateDownloadTempInfo(randomAccessFile,fileDownloadEntity,tempInfo);
			} else {
				downloadPartSize = tempInfo.getPartSize();
			}
			int size = fileDownloadEntity.getSize();
			log.info("size = {}", size);
			int cnt = size % downloadPartSize == 0 ? size / downloadPartSize
					: size / downloadPartSize + 1;
			for (int i = 0; i < cnt; i++) {
				service.submit(
						getDownloadTas(i, downloadPartSize, size, tempInfo,
								fileDownloadEntity, rwLock, randomAccessFile));
                
			}
			service.shutdown();
			while (!service.isTerminated()) {
				TimeUnit.SECONDS.sleep(2);
				log.info("download {} ......", fileName);
			}
			if (!tempInfo.isFinish()) {
				log.info("finishCnt = {} partCnt = {}",
						tempInfo.getFinish().size(), cnt);
				log.info("download {} fail", fileName);
			} else {
                randomAccessFile.close();
                String saveName = reNameFile(fileName, downloading, tempInfo,null);
				log.info("下载完成 {} save to {}", fileName, saveName);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
    }

	private String reNameFile(String fileName, File downloading,
			DownloadTempInfo tempInfo, String pre) {
	    if (StrUtil.isNotBlank(pre)){
            StringBuilder sb = new StringBuilder(fileName);
            sb.insert(sb.lastIndexOf("."),"_"+pre);
            fileName = sb.toString();
        }
		if (downloading.renameTo(new File(fileName))) {
			try (RandomAccessFile finishFile = new RandomAccessFile(fileName,
					"rw")) {
				// 重命名成功 删除尾部信息
				finishFile.setLength(tempInfo.getSize());
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			log.error("{} rename fail",downloading.getName());
			if (StrUtil.isBlank(pre)) {
				log.info("{} add fix try rename" ,downloading.getName());
				//添加后缀尝试重命名
				return reNameFile(fileName, downloading, tempInfo,
						UUID.randomUUID().toString(true));
			}else{
                log.error("{} add fix try rename fail , please check" ,downloading.getName());
            }
        }
		return fileName;
    }

    /**
	 * 获取文件尾部的下载进度信息
	 * 
	 * @return com.yxhpy.entity.DownloadTempInfo
	 * @since 2022年06月16日 17:59:13
	 * @author zengzhixing
	 */
	private DownloadTempInfo getDownloadTempInfo(
			RandomAccessFile randomAccessFile,
			FileDownloadEntity fileDownloadEntity) {
		// 读取最后的下载信息
		try {
			int size = fileDownloadEntity.getSize();
			randomAccessFile.seek(size);
            String line = randomAccessFile.readLine();
            if (line == null) {
				return null;
			}
            System.out.println(line);
			return JSONUtil.toBean(line,
					DownloadTempInfo.class);
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * 更新文件尾部的下载进度信息
	 * 
	 * @return void
	 * @since 2022年06月16日 17:59:30
	 * @author zengzhixing
	 */
	private void updateDownloadTempInfo(
			RandomAccessFile randomAccessFile,
			FileDownloadEntity fileDownloadEntity,
            DownloadTempInfo tempInfo) {
		// 更新下载信息到最后一行
		try {
			int size = fileDownloadEntity.getSize();
			randomAccessFile.setLength(size);
			randomAccessFile.seek(size);
			randomAccessFile.write(tempInfo.toSaveJson().getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			e.getStackTrace();
		}
	}

	/**
	 * 获取文件片段的下载任务
	 * 
	 * @return java.lang.Runnable
	 * @since 2022年06月16日 17:59:53
	 * @author zengzhixing
	 */
	private Runnable getDownloadTas(int i,int downloadPartSize,int size,
                                    DownloadTempInfo tempInfo,
                                    FileDownloadEntity fileDownloadEntity,
                                    ReentrantReadWriteLock rwLock,RandomAccessFile randomAccessFile){
		return () -> {
			int start = i * downloadPartSize;
			int end = Math.min(size - 1, (i + 1) * downloadPartSize - 1);
			if (tempInfo.getFinish().contains(i)) {
				log.info("{} to {} has download", start, end);
				return;
			}
			int reTry = RequestConfig.MULTI_TRY;
			while (reTry > 0) {
				reTry--;
				try {
					log.info("start = {} end = {}", start, end);
					ResponseBody body = Request.download(
							fileDownloadEntity.getUrl(),
							Headers.of("Referer",
									"https://www.aliyundrive.com/", "Range",
									"bytes=" + start + "-" + end));
					if (body != null) {
						byte[] bytes = body.bytes();
						SafeFile safeFile = SafeFile.getInstance();
						safeFile.handler(bytes, false);
                        WriteLock writeLock = rwLock.writeLock();
						try {
							writeLock.lock();
							randomAccessFile.seek(start);
							randomAccessFile.write(bytes);
							tempInfo.getFinish().add(i);
							// 更新下载进度信息到文件中
							updateDownloadTempInfo(randomAccessFile,
									fileDownloadEntity, tempInfo);
						} finally {
							writeLock.unlock();
						}
					}
					return;
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			log.info("{} try download fail");
		};
    }
	

    private final Queue<DownloadEntity> downloadEntities = new LinkedBlockingQueue<>();

    public void download(String parentPath, String parentPathId) {
        FolderEntity folder = getFolderFileList(parentPathId);
        for (ItemsEntity item : folder.getItems()) {
            String path = parentPath + File.separator + item.getName();
            if ("folder".equals(item.getType())) {
                addFileId(path, item.getFileId());
                downloadEntities.offer(new DownloadEntity(path, item.getFileId()));
            }
            if ("file".equals(item.getType())) {
                downloadFile(parentPath, item.getName(), item.getFileId());
            }
        }
        DownloadEntity downloadEntity = downloadEntities.poll();
        while (downloadEntity != null) {
            download(downloadEntity.getLocalPath(), downloadEntity.getRemoteId());
            downloadEntity = downloadEntities.poll();
        }
    }

    public FileExistsEntity complete(String fileId, String uploadId) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("drive_id", loginInfo.getDefaultDriveId());
        map.put("file_id", fileId);
        map.put("upload_id", uploadId);
        HttpResponse response = request.postJson("https://api.aliyundrive.com/v2/file/complete", map);
        return JsonUtils.responseToBean(response, FileExistsEntity.class);
    }

    private final HashMap<File, String> fileIdMap = new HashMap<>();

    public String getFileIdByPath(String path) {
        return fileIdMap.get(new File(path));
    }

    public void addFileId(String path, String fileId) {
        File file = new File(path);
        if (!Objects.equals(fileIdMap.get(file), fileId)) {
            log.info("添加文件id文件（夹）" + path + ":" + fileId);
            fileIdMap.put(file, fileId);
        }
    }

    public void removeFileId(String path) {
        log.info("删除文件id文件（夹）" + path);
        fileIdMap.remove(new File(path));
    }

    public void uploadSingleFile(String realFileName, String remoteParentId) {
        EncFileInfoEntity fileInfo = getFileInfo(realFileName);
        FileExistsEntity fileExists = fileExists(remoteParentId, fileInfo);
        if ("PreHashMatched".equals(fileExists.getCode())) {
            FileExistsEntity file = createFile(remoteParentId, fileInfo);
            addFileId(realFileName, file.getFileId());
            log.info(realFileName + "快传成功");
        } else {
            List<PartInfoListEntity> partInfoList = fileExists.getPartInfoList();
            if (partInfoList != null) {
                try (FileInputStream fileInputStream = new FileInputStream(realFileName)) {
                    int available = fileInputStream.available();
                    int i = 0;
                    while (available > 0) {
                        byte[] bytes = new byte[Math.min(available, PART_SIZE)];
                        fileInputStream.read(bytes);
                        SafeFile safeFile = SafeFile.getInstance();
                        safeFile.handler(bytes, true);
                        PartInfoListEntity partInfoListEntity = partInfoList.get(i);
                        Response upload = Request.upload(partInfoListEntity.getUploadUrl(), bytes);
                        int code = upload.code();
                        if (code == 200) {
                            log.info("上传成功：" + partInfoListEntity);
                        } else {
                            log.info("上传失败：" + partInfoListEntity);
                        }
                        if (partInfoList.size() == ++i) {
                            FileExistsEntity complete = complete(fileExists.getFileId(), fileExists.getUploadId());
                            addFileId(realFileName, complete.getFileId());
                            log.info("complete:" + complete);
                            log.info(realFileName + "上传完毕");
                        }
                        available = fileInputStream.available();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void uploadFile(String remoteParentId, String filePath) {
        addFileId(filePath, remoteParentId);
        File file = new File(filePath);
        for (String fileName : Objects.requireNonNull(file.list())) {
            String realFileName = filePath + File.separator + fileName;
            if (FileUtil.isDirectory(realFileName)) {
                FolderCreateEntity withFolders = createWithFolders(remoteParentId, fileName);
                if (withFolders.isExist()) {
                    log.info("该目录已经存在：root" + realFileName.replace(BASE_PATH, ""));
                } else {
                    log.info("服务器创建目录成功：root" + realFileName.replace(BASE_PATH, ""));
                }
                uploadFile(withFolders.getFileId(), realFileName);
            }
            if (FileUtil.isFile(realFileName)) {
                uploadSingleFile(realFileName, remoteParentId);
            }
        }
    }

    public String getFileId(String... path) {
        String fileId = "root";
        for (String name : path) {
            FolderEntity fileList = getFolderFileList(fileId);
            ItemsEntity item = fileList.getItemByName(name);
            if (item == null) {
                FolderCreateEntity withFolders = createWithFolders(fileId, name);
                fileId = withFolders.getFileId();
            } else {
                fileId = item.getFileId();
            }
        }
        return fileId;
    }

    public String getFolderId(String path) {
        File file = new File(path);
        File file2 = new File(BASE_PATH);
        Stack<String> stack = new Stack<>();
        if (Objects.equals(file, file2)) {
            return remoteFileId;
        }
        do {
            stack.push(file.getName());
            file = file.getParentFile();
        } while (!Objects.equals(file, file2));
        String parentId = remoteFileId;
        if (parentId == null) {
            int size = stack.size();
            String[] paths = new String[size];
            for (int i = 0; i < size; i++) {
                paths[i] = stack.pop();
            }
            return getFileId(paths);
        }
        String absPath = BASE_PATH;
        while (!stack.isEmpty()) {
            String pop = stack.pop();
            absPath += (File.separator + pop);
            if (FileUtil.isDirectory(absPath)) {
                FolderCreateEntity withFolders = createWithFolders(parentId, pop);
                parentId = withFolders.getFileId();
            } else {
                List<ItemsEntity> itemsEntity = getFolderFileList(parentId).getItems();
                for (ItemsEntity entity : itemsEntity) {
                    if (Objects.equals(pop, entity.getName())) {
                        return entity.getFileId();
                    }
                }
            }
            addFileId(absPath, parentId);
        }
        return parentId;
    }

    public void initDefault() {
        fileIdMap.clear();
        EXISTS_FILE_NAMES.clear();
        remoteFileId = getFolderId(BASE_PATH + File.separator + REMOTE_PATH);
        log.info("开始执行同步，该操作只执行一次，后续本地文件会向远程同步");
        uploadFile(remoteFileId, BASE_PATH);
        log.info("同步执行完毕，后续只会本地同步到远程");
        startFileWatch();
    }

    public void initDownloader() {
        fileIdMap.clear();
        EXISTS_FILE_NAMES.clear();
        remoteFileId = getFolderId(BASE_PATH + File.separator + REMOTE_PATH);
        log.info("当前为服务器同步本地模式，忽略本地修改");
        while (true) {
            download(BASE_PATH, remoteFileId);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void startFileWatch() {
        try {
            log.info("文件监控器开启");
            FileListener.start(BASE_PATH, this);
            log.info("文件监控器开启成功");
        } catch (Exception e) {
            log.info("文件监控器开启失败");
            e.printStackTrace();
        }
    }

    public void run() {
        File basePath = new File(BASE_PATH);
        if (!basePath.exists()) {
            basePath.mkdirs();
        }
        int delayRestart = 5000;
        File file = new File("loginInfo");
        if (file.exists()) {
            PdsLoginResult loginInfo = JsonUtils.readBean("loginInfo", PdsLoginResult.class);
            String refreshToken = loginInfo.getRefreshToken();
            if (!refreshToken(refreshToken)) {
                startLogin();
            }
        } else {
            startLogin();
        }
        while (true) {
            try {
                if (RequestConfig.PROVIDER) {
                    initDefault();
                } else {
                    initDownloader();
                }
                Thread.currentThread().join();
            } catch (Exception e) {
                log.error("错误：" + e.getMessage());
                if (RequestConfig.ENABLE_RESTART) {
                    log.info(delayRestart + "毫秒后开始重新启动项目");
                    try {
                        Thread.sleep(delayRestart);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                } else {
                    break;
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        BaseAliPan baseAliPan = new BaseAliPan();
        baseAliPan.run();
    }
}
