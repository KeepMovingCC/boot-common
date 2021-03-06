package com.github.hdy.common.util;


import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import com.github.hdy.common.entity.ColumnEntity;
import com.github.hdy.common.entity.GenConfig;
import com.github.hdy.common.entity.TableEntity;
import com.github.hdy.common.entity.TableInfo;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 代码生成
 *
 * @author 贺大爷
 * @date 2019/6/25
 */
@Slf4j
@UtilityClass
public class GenUtils {
    private static final String ENTITY_JAVA_VM = "Entity.java.vm";
    private static final String DAO_JAVA_VM = "Dao.java.vm";
    private static final String SERVICE_JAVA_VM = "Service.java.vm";
    private static final String CONTROLLER_JAVA_VM = "Controller.java.vm";

    private List<String> getTemplates() {
        List<String> templates = new ArrayList<>();
        templates.add("templates/generator/Entity.java.vm");
        templates.add("templates/generator/Dao.java.vm");
        templates.add("templates/generator/Service.java.vm");
        templates.add("templates/generator/Controller.java.vm");
        return templates;
    }

    /**
     * 生成代码
     */
    public void generatorCode(GenConfig genConfig, TableInfo table,
                              List<Map<String, Object>> columns, ZipOutputStream zip) throws Exception {
        //配置信息
        Configuration config = getConfig();
        boolean hasBigDecimal = false;
        boolean hasDate = false;
        //表信息
        TableEntity tableEntity = new TableEntity();
        tableEntity.setTableName(table.getTableName());

        if (StrUtil.isNotBlank(genConfig.getComments())) {
            tableEntity.setComments(genConfig.getComments());
        } else {
            tableEntity.setComments(table.getTableComment());
        }

        String tablePrefix;
        if (StrUtil.isNotBlank(genConfig.getTablePrefix())) {
            tablePrefix = genConfig.getTablePrefix();
        } else {
            tablePrefix = config.getString("tablePrefix");
        }

        //表名转换成Java类名
        String className = tableToJava(tableEntity.getTableName(), tablePrefix);
        tableEntity.setCaseClassName(className);
        tableEntity.setLowerClassName(StringUtils.uncapitalize(className));

        //列信息
        List<ColumnEntity> columnList = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            ColumnEntity columnEntity = new ColumnEntity();
            columnEntity.setColumnName(Strings.toString(column.get("columnName")));
            columnEntity.setDataType(Strings.toString(column.get("dataType")));
            columnEntity.setComments(Strings.toString(column.get("columnComment")));
            String extra = Strings.toString(column.get("extra"));
            columnEntity.setExtra(Strings.toString(Strings.isNull(extra) ? null : extra.toLowerCase()));
            columnEntity.setIsNullBle(Strings.toString(column.get("isNullBle")));
            //列名转换成Java属性名
            String attrName = columnToJava(columnEntity.getColumnName());
            columnEntity.setCaseAttrName(attrName);
            columnEntity.setLowerAttrName(StringUtils.uncapitalize(attrName));

            //列的数据类型，转换成Java类型
            String attrType = config.getString(columnEntity.getDataType(), "unknowType");
            columnEntity.setAttrType(attrType);
            if (!hasBigDecimal && "BigDecimal".equals(attrType)) {
                hasBigDecimal = true;
            }
            if (!hasDate && "Date".equals(attrType)) {
                hasDate = true;
            }
            //是否主键
            if ("PRI".equalsIgnoreCase(Strings.toString(column.get("columnKey"))) && tableEntity.getPk() == null) {
                tableEntity.setPk(columnEntity);
                if (Strings.isNull(columnEntity.getComments()))
                    columnEntity.setComments("主键ID");
            }
            if (Strings.isNull(columnEntity.getComments()))
                columnEntity.setComments(columnEntity.getLowerAttrName());
            columnList.add(columnEntity);
        }
        tableEntity.setColumns(columnList);

        //没主键，则第一个字段为主键
        if (tableEntity.getPk() == null) {
            tableEntity.setPk(tableEntity.getColumns().get(0));
        }

        //设置velocity资源加载器
        Properties prop = new Properties();
        prop.put("file.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
        Velocity.init(prop);
        //封装模板数据
        Map<String, Object> map = new HashMap<>(16);
        map.put("tableName", tableEntity.getTableName());
        map.put("pk", tableEntity.getPk());
        map.put("className", tableEntity.getCaseClassName());
        map.put("classname", tableEntity.getLowerClassName());
        map.put("pathName", Strings.camelToCustom(tableEntity.getLowerClassName(), '/'));
        map.put("columns", tableEntity.getColumns());
        map.put("hasBigDecimal", hasBigDecimal);
        map.put("hasDate", hasDate);
        map.put("datetime", DateUtil.now());

        if (StrUtil.isNotBlank(genConfig.getComments())) {
            map.put("comments", genConfig.getComments());
        } else {
            map.put("comments", tableEntity.getComments());
        }

        if (StrUtil.isNotBlank(genConfig.getAuthor())) {
            map.put("author", genConfig.getAuthor());
        } else {
            map.put("author", config.getString("author"));
        }

        if (StrUtil.isNotBlank(genConfig.getModuleName())) {
            map.put("moduleName", genConfig.getModuleName());
        } else {
            map.put("moduleName", config.getString("moduleName"));
        }

        if (StrUtil.isNotBlank(genConfig.getPackageName())) {
            map.put("package", genConfig.getPackageName());
            map.put("mainPath", genConfig.getPackageName());
        } else {
            map.put("package", config.getString("package"));
            map.put("mainPath", config.getString("mainPath"));
        }
        VelocityContext context = new VelocityContext(map);

        //获取模板列表
        List<String> templates = getTemplates();
        for (String template : templates) {
            //渲染模板
            StringWriter sw = new StringWriter();
            Template tpl = Velocity.getTemplate(template, CharsetUtil.UTF_8);
            tpl.merge(context, sw);

            try {
                //添加到zip
                zip.putNextEntry(new ZipEntry(Objects
                        .requireNonNull(getFileName(template, tableEntity.getCaseClassName()
                                , map.get("package").toString(), map.get("moduleName").toString()))));
                IoUtil.write(zip, CharsetUtil.UTF_8, false, sw.toString());
                IoUtil.close(sw);
                zip.closeEntry();
            } catch (IOException e) {
                throw new Exception("渲染模板失败，表名：" + tableEntity.getTableName(), e);
            }
        }
    }


    /**
     * 列名转换成Java属性名
     */
    private String columnToJava(String columnName) {
        return WordUtils.capitalizeFully(columnName, new char[]{'_'}).replace("_", "");
    }

    /**
     * 表名转换成Java类名
     */
    private String tableToJava(String tableName, String tablePrefix) {
        if (StringUtils.isNotBlank(tablePrefix)) {
            tableName = tableName.replace(tablePrefix, "");
        }
        return columnToJava(tableName);
    }

    /**
     * 获取配置信息
     */
    private Configuration getConfig() throws Exception {
        try {
            return new PropertiesConfiguration("generator.properties");
        } catch (ConfigurationException e) {
            throw new Exception("获取配置文件失败，", e);
        }
    }

    /**
     * 获取文件名
     */
    private String getFileName(String template, String className, String packageName, String moduleName) {
        String packagePath = "hdy-code" + File.separator + "src" + File.separator + "main" + File.separator + "java" + File.separator;
        if (StringUtils.isNotBlank(packageName)) {
            packagePath += packageName.replace(".", File.separator) + File.separator + moduleName + File.separator;
        }

        if (template.contains(ENTITY_JAVA_VM)) {
            return packagePath + "entity" + File.separator + className + ".java";
        }

        if (template.contains(DAO_JAVA_VM)) {
            return packagePath + "dao" + File.separator + className + "Dao.java";
        }

        if (template.contains(SERVICE_JAVA_VM)) {
            return packagePath + "service" + File.separator + className + "Service.java";
        }


        if (template.contains(CONTROLLER_JAVA_VM)) {
            return packagePath + "controller" + File.separator + className + "Controller.java";
        }

        return null;
    }
}
