package com.yxhpy.utils;

import com.yxhpy.BaseAliPan;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;

public class FindFolder {

    public static String target = "";


    public static void main(String[] args) {
        // 指定要查找的文件夹名称
        String folderName = "WeChat Files";
        traverseFolder(new File("C:\\Users"), folderName);
        System.out.println("找到文件夹：" + target);
    }

    /**
     * 递归遍历文件夹中的所有文件和目录
     * @param folder 文件夹对象
     */
    public static void traverseFolder(File folder,String keyword) {
        if (folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    traverseFolder(file,keyword);
                }
            }
            if(folder.getName().equals(keyword)){
                if(folder.getParentFile().getName().equals("Documents")){
                    File[] listFiles = folder.listFiles();
                    for (File listFile : listFiles) {
                        if (listFile.getName().startsWith("wxid_")){
                            BaseAliPan.REMOTE_PATH = listFile.getName();
                            File[] listFiles1 = listFile.listFiles();
                            for (File file : listFiles1) {
                                if(file.getName().equals("FileStorage")){
                                    target = file.getAbsolutePath();
//                                    File[] files1 = file.listFiles();
//                                    for (File file1 : files1) {
//                                        if(file1.getName().equals("Video")){
//                                            target = file1.getAbsolutePath();
//                                        }
//                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
