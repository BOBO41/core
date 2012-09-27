package com.dotmarketing.services;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.velocity.runtime.resource.ResourceManager;

import com.dotmarketing.beans.Identifier;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.CacheLocator;
import com.dotmarketing.cache.FieldsCache;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.exception.DotSecurityException;
import com.dotmarketing.factories.InodeFactory;
import com.dotmarketing.portlets.categories.business.CategoryAPI;
import com.dotmarketing.portlets.categories.model.Category;
import com.dotmarketing.portlets.contentlet.business.ContentletAPI;
import com.dotmarketing.portlets.contentlet.business.DotContentletStateException;
import com.dotmarketing.portlets.contentlet.model.Contentlet;
import com.dotmarketing.portlets.form.business.FormAPI;
import com.dotmarketing.portlets.htmlpages.model.HTMLPage;
import com.dotmarketing.portlets.structure.business.FieldAPI;
import com.dotmarketing.portlets.structure.model.Field;
import com.dotmarketing.portlets.structure.model.Structure;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.ConfigUtils;
import com.dotmarketing.util.InodeUtils;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.UtilMethods;
import com.dotmarketing.velocity.DotResourceCache;
import com.liferay.portal.model.User;
import com.liferay.util.FileUtil;

/**
 * @author will
 */
public class ContentletMapServices {

	private static CategoryAPI categoryAPI = APILocator.getCategoryAPI();

	public static CategoryAPI getCategoryAPI() {
		return categoryAPI;
	}

	public static void setCategoryAPI(CategoryAPI categoryAPI) {
		ContentletMapServices.categoryAPI = categoryAPI;
	}

	public static void invalidate(Contentlet contentlet) throws DotDataException, DotSecurityException {
		invalidate(contentlet, true);
		invalidate(contentlet, false);
	}

	public static void invalidate(Contentlet content, boolean EDIT_MODE) throws DotDataException, DotSecurityException {
		removeContentletMapFile(content, EDIT_MODE);
	}

	public static InputStream buildVelocity(Contentlet content, boolean EDIT_MODE) throws DotDataException, DotSecurityException, DotContentletStateException {
		InputStream result;
		ContentletAPI conAPI = APILocator.getContentletAPI();
		User systemUser = APILocator.getUserAPI().getSystemUser();

		if (!InodeUtils.isSet(content.getInode())|| !InodeUtils.isSet(content.getIdentifier())) {
			throw new DotContentletStateException("The contentlet inode and identifier must be set");
		}
		// let's write this puppy out to our file
		StringBuilder sb = new StringBuilder();
		String conTitle = conAPI.getName(content, APILocator.getUserAPI().getSystemUser(), true);
		// CONTENTLET CONTROLS BEGIN
		// To edit the look, see
		// WEB-INF/velocity/static/preview/content_controls.vtl
		sb.append("#set( $dotcms_content_").append(content.getIdentifier()).append(" = ${contents.getEmptyMap()})\n");
//		Was put in to fix DOTCMS-995 but it caused DOTCMS-1210.
//      I actually think it should be fine passed the ctx which is a chained context here
//		sb.append("#set($velocityContext = $UtilMethods.pushVelocityContext($velocityContext))\n");
//		sb.append("$!velocityContext.put(\"content\",$content)\n");

		sb.append("$!dotcms_content_").append(content.getIdentifier()).append(".put(\"permission\", $EDIT_CONTENT_PERMISSION").append(content.getIdentifier()).append(" )\n");
		sb.append("$!dotcms_content_").append(content.getIdentifier()).append(".put(\"inode\", '").append(content.getInode()).append("'  )\n");
		sb.append("$!dotcms_content_").append(content.getIdentifier()).append(".put(\"identifier\", '").append(content.getIdentifier()).append("'  )\n");
		sb.append("$!dotcms_content_").append(content.getIdentifier()).append(".put(\"structureInode\", '").append(content.getStructureInode()).append("'  )\n");
		sb.append("$!dotcms_content_").append(content.getIdentifier()).append(".put(\"contentTitle\", \"").append(UtilMethods.espaceForVelocity(conTitle)).append("\" )\n");
		sb.append("$!dotcms_content_").append(content.getIdentifier()).append(".put(\"detailPageURI\", \"").append(getDetailPageURI(content)).append("\"  )\n");
		Structure structure = content.getStructure();

		String modDateStr = UtilMethods.dateToHTMLDate((Date) content.getModDate(), "yyyy-MM-dd H:mm:ss");
		sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"modDate\", $date.toDate(\"yyyy-MM-dd H:mm:ss\", \"").append(modDateStr).append("\")))\n");
		sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"contentLastModDate\", $date.toDate(\"yyyy-MM-dd H:mm:ss\", \"").append(modDateStr).append("\")))\n");
		sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"contentLastModUserId\", \"").append(content.getModUser()).append("\"))\n");
		if (content.getOwner() != null)
			sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"contentOwnerId\", \"").append(content.getOwner()).append("\"))\n");

		// Structure fields
		List<Field> fields = FieldsCache.getFieldsByStructureInode(content.getStructureInode());
		Iterator<Field> fieldsIt = fields.iterator();

		String widgetCode = "";

		while (fieldsIt.hasNext()) {
			Field field = (Field) fieldsIt.next();

			sb.append("\n\n##Set Field ").append(field.getFieldName()).append(" properties\n");

			String contField = field.getFieldContentlet();
			String contFieldValue = null;
			Object contFieldValueObject = null;
			FieldAPI fdAPI = APILocator.getFieldAPI();
			String velPath = (!EDIT_MODE) ? "live/" : "working/";
			if(fdAPI.isElementConstant(field)){
				if(field.getVelocityVarName().equals("widgetPreexecute")){
					continue;
				}
				if(field.getVelocityVarName().equals("widgetCode")){
//					widgetCode = "#set($_dummy = $!dotcms_content_" + content.getIdentifier() + ".put(\"" + field.getVelocityVarName() + "\", $UtilMethods.evaluateVelocity($UtilMethods.restoreVariableForVelocity('"
//					+ UtilMethods.espaceVariableForVelocity(field.getValues()) + "'), $velocityContext)))\n";
//					widgetCode = "#set($_dummy = $!dotcms_content_" + content.getIdentifier() + ".put(\"" + field.getVelocityVarName() + "\", \"" + UtilMethods.espaceForVelocity(field.getValues()).trim() + "\"))\n";
//					widgetCode = "#set( $fieldStringWriter" + content.getInode() + field.getInode() + " = $stringsapi.getEmptyStringWriter())\n";
//					widgetCode += "$UtilMethods.getVelocityTemplate(\"" + folderPath + content.getInode() + "_" + field.getInode()  + "." + Config.getStringProperty("VELOCITY_FIELD_EXTENSION") +  "\").merge($context, $fieldStringWriter" + content.getInode() + field.getInode()  + ")\n";
//					widgetCode += "#set($_dummy = $!dotcms_content_" + content.getIdentifier() + ".put(\"" + field.getVelocityVarName() + "\", $fieldStringWriter" + content.getInode() +  field.getInode()  + ".toString()))\n";
					widgetCode = "#set($_dummy = $!dotcms_content_" + content.getIdentifier() + ".put(\"" + field.getVelocityVarName() + "\", $velutil.mergeTemplate(\"" + velPath + content.getInode() + "_" + field.getInode()  + "." + Config.getStringProperty("VELOCITY_FIELD_EXTENSION") + "\")))\n";
					continue;
				}else{
//					sb.append("#set($_dummy = $!dotcms_content_" + content.getIdentifier() + ".put(\"" + field.getVelocityVarName() + "\", $UtilMethods.evaluateVelocity($UtilMethods.restoreVariableForVelocity('"
//							+ UtilMethods.espaceVariableForVelocity(field.getValues()) + "'), $velocityContext)))\n");
//					sb.append("#set($_dummy = $!dotcms_content_" + content.getIdentifier() + ".put(\"" + field.getVelocityVarName() + "\", \"" + UtilMethods.espaceForVelocity(field.getValues()).trim() + "\"))\n");
//					sb.append("#set( $fieldStringWriter" +  content.getInode() + field.getInode()  + " = $stringsapi.getEmptyStringWriter())\n");
//					sb.append("$UtilMethods.getVelocityTemplate(\"" + folderPath +  content.getInode() + "_" + field.getInode() + "." + Config.getStringProperty("VELOCITY_FIELD_EXTENSION") + "\").merge($context, $fieldStringWriter" + content.getInode() + field.getInode()  + ")\n");
//					sb.append("#set($_dummy = $!dotcms_content_" + content.getIdentifier() + ".put(\"" + field.getVelocityVarName() + "\", $fieldStringWriter" + content.getInode() + field.getInode()  + ".toString()))\n");
					if(field.getValues().contains("$") || field.getValues().contains("#")){
						sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("\", $velutil.mergeTemplate(\"").append(velPath).append(content.getInode()).append("_").append(field.getInode()).append(".").append(Config.getStringProperty("VELOCITY_FIELD_EXTENSION")).append("\")))\n");
					}else{
						sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("\", \"").append(UtilMethods.espaceForVelocity(field.getValues()).trim()).append("\"))\n");
					}
					continue;
				}

			}
			if (UtilMethods.isSet(contField)) {
				try {
					contFieldValueObject = conAPI.getFieldValue(content, field);
					contFieldValue = contFieldValueObject == null ? "" : contFieldValueObject.toString();
					// contFieldValueObject = PropertyUtils.getProperty(content,
					// contField);
					// contFieldValue = contFieldValueObject == null ? "" :
					// contFieldValueObject.toString();
				} catch (Exception e) {
					Logger.error(ContentletMapServices.class, "writeContentletToFile: " + e.getMessage());
				}
				if (!field.getFieldType().equals(Field.FieldType.DATE_TIME.toString()) && !field.getFieldType().equals(Field.FieldType.DATE.toString())
						&& !field.getFieldType().equals(Field.FieldType.TIME.toString())) {
					if (fdAPI.isNumeric(field)) {
						sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("\", ").append(contFieldValue).append("))\n");
					} else {
//						sb.append("#set($_dummy = $!dotcms_content_" + content.getIdentifier() + ".put(\"" + field.getVelocityVarName() + "\", $UtilMethods.evaluateVelocity($UtilMethods.restoreVariableForVelocity('"
//								+ UtilMethods.espaceVariableForVelocity(contFieldValue) + "'), $velocityContext)))\n");
//						sb.append("#set($_dummy = $!dotcms_content_" + content.getIdentifier() + ".put(\"" + field.getVelocityVarName() + "\", \"" + UtilMethods.espaceForVelocity(contFieldValue).trim() + "\"))\n");
//						sb.append("#set( $fieldStringWriter" + content.getInode() + field.getInode()  + " = $stringsapi.getEmptyStringWriter())\n");
//						sb.append("$UtilMethods.getVelocityTemplate(\"" + folderPath + content.getInode() + "_" + field.getInode() + "." + Config.getStringProperty("VELOCITY_FIELD_EXTENSION") + "\").merge($context, $fieldStringWriter" + content.getInode() + field.getInode()  + ")\n");
//						sb.append("#set($_dummy = $!dotcms_content_" + content.getIdentifier() + ".put(\"" + field.getVelocityVarName() + "\", $fieldStringWriter" + content.getInode() + field.getInode()  +".toString()))\n");
						if(contFieldValue.contains("$") || contFieldValue.contains("#")){
							sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("\", $velutil.mergeTemplate(\"").append(velPath).append(content.getInode()).append("_").append(field.getInode()).append(".").append(Config.getStringProperty("VELOCITY_FIELD_EXTENSION")).append("\")))\n");
						}else{
							sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("\", \"").append(UtilMethods.espaceForVelocity(contFieldValue).trim()).append("\"))\n");
						}
					}
				}

			}

			if (field.getFieldType().equals(Field.FieldType.TEXT.toString()) || field.getFieldType().equals(Field.FieldType.TEXT_AREA.toString())
					|| field.getFieldType().equals(Field.FieldType.WYSIWYG.toString())) {
				// sb.append("#set( $" + field.getVelocityVarName() + "
				// =\"#fixBreaks($" + field.getVelocityVarName() + ")\")\n");
				// sb.append("$!dotcms_content_" + content.getIdentifier() + ".put(\""+field.getVelocityVarName()+"\",
				// \"" + UtilMethods.fixBreaks(contFieldValue) + " \" )\n");

			} else if (field.getFieldType().equals(Field.FieldType.IMAGE.toString())) {
				String identifierValue = content.getStringProperty(field.getVelocityVarName());
				if( InodeUtils.isSet(identifierValue) ) {
					if (EDIT_MODE){
						sb.append("#set( $").append(field.getVelocityVarName()).append(" = $filetool.getFile('").append(identifierValue).append("',false))\n");
					}else{
						sb.append("#set( $").append(field.getVelocityVarName()).append(" = $filetool.getFile('").append(identifierValue).append("',true))\n");
					}
				}else{
					sb.append("#set( $").append(field.getVelocityVarName()).append(" = $filetool.getNewFile())\n");
				}

				sb.append("#set($_dummy = $!dotcms_content_").append( content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("ImageInode\", $").append(field.getVelocityVarName()).append(".getInode() ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("ImageIdentifier\", $").append(field.getVelocityVarName()).append(".getIdentifier() ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("ImageWidth\", $").append(field.getVelocityVarName()).append(".getWidth() ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("ImageHeight\", $").append(field.getVelocityVarName()).append(".getHeight() ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("ImageExtension\", $UtilMethods.espaceForVelocity($").append(field.getVelocityVarName()).append(".getExtension()) ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("ImageURI\", $filetool.getURI($").append(field.getVelocityVarName()).append(") ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("ImageTitle\", $UtilMethods.espaceForVelocity($").append(field.getVelocityVarName()).append(".getTitle()) ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("ImageFriendlyName\", $UtilMethods.espaceForVelocity($").append(field.getVelocityVarName()).append(".getFriendlyName()) ))\n");

				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("ImagePath\", $UtilMethods.espaceForVelocity($").append(field.getVelocityVarName()).append(".getPath()) ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("ImageName\", $UtilMethods.espaceForVelocity($").append(field.getVelocityVarName()).append(".getFileName()) ))\n");

			} else if (field.getFieldType().equals(Field.FieldType.FILE.toString())) {
				String identifierValue = content.getStringProperty(field.getVelocityVarName());
				if( InodeUtils.isSet(identifierValue) ) {
					if (EDIT_MODE){
						sb.append("#set( $").append(field.getVelocityVarName()).append("Object = $filetool.getFile('").append(identifierValue).append("',false))\n");
					}else{
						sb.append("#set( $").append(field.getVelocityVarName()).append("Object = $filetool.getFile('").append(identifierValue).append("',true))\n");
					}
				}else{
					sb.append("#set( $").append(field.getVelocityVarName()).append("Object = $filetool.getNewFile())\n");
				}


				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("FileInode\", $").append(field.getVelocityVarName()).append("Object.getInode() ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("FileIdentifier\", $").append(field.getVelocityVarName()).append("Object.getIdentifier() ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("FileFriendlyName\", $UtilMethods.espaceForVelocity($").append(field.getVelocityVarName()).append("Object.getFriendlyName()) ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("FileExtension\", $UtilMethods.espaceForVelocity($").append(field.getVelocityVarName()).append("Object.getExtension()) ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("FileURI\", $filetool.getURI($").append(field.getVelocityVarName()).append("Object) ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("FileTitle\", $").append(field.getVelocityVarName()).append("Object.getTitle() ))\n");

				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("FilePath\", $UtilMethods.espaceForVelocity($").append(field.getVelocityVarName()).append("Object.getPath()) ))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("FileName\", $UtilMethods.espaceForVelocity($").append(field.getVelocityVarName()).append("Object.getFileName()) ))\n");

			} //http://jira.dotmarketing.net/browse/DOTCMS-2178
			else if (field.getFieldType().equals(Field.FieldType.BINARY.toString())) {
				java.io.File binFile;
				String fileName = "";
				String filesize = "";
				try {
					binFile = content.getBinary(field.getVelocityVarName());
					if(binFile != null) {
						fileName = binFile.getName();
						filesize = FileUtil.getsize(binFile);
					}
				} catch (IOException e) {
					Logger.error(ContentletServices.class, "Unable to retrive binary file for content id " + content.getIdentifier() + " field " + field.getVelocityVarName(), e);
					continue;
				}
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("BinaryFileTitle\", \"").append(UtilMethods.espaceForVelocity(fileName)).append("\"))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("BinaryFileSize\", \"").append(UtilMethods.espaceForVelocity(filesize)).append("\"))\n");
				String binaryFileURI = fileName.length()>0? UtilMethods.espaceForVelocity("/contentAsset/raw-data/"+content.getIdentifier()+"/"+ field.getVelocityVarName() + "/" + content.getInode()):"";
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("BinaryFileURI\", \"").append(binaryFileURI).append("\"))\n");
			} else if (field.getFieldType().equals(Field.FieldType.SELECT.toString())) {
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("SelectLabelsValues\", \""
				        ).append( field.getValues().replaceAll("\\r\\n", " ").replaceAll("\\n", " ")).append("\"))\n");

			} else if (field.getFieldType().equals(Field.FieldType.RADIO.toString())) {
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("RadioLabelsValues\", \""
				        ).append(field.getValues().replaceAll("\\r\\n", " ").replaceAll("\\n", " ")).append("\"))\n");

			} else if (field.getFieldType().equals(Field.FieldType.CHECKBOX.toString())) {
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("CheckboxLabelsValues\", \""
				        ).append(field.getValues().replaceAll("\\r\\n", " ").replaceAll("\\n", " ")).append("\"))\n");

			} else if (field.getFieldType().equals(Field.FieldType.DATE.toString())) {
				String shortFormat = "";
				String dbFormat = "";
				if (contFieldValueObject != null && contFieldValueObject instanceof Date) {
					shortFormat = UtilMethods.dateToHTMLDate((Date) contFieldValueObject, "MM/dd/yyyy");
					dbFormat = UtilMethods.dateToHTMLDate((Date) contFieldValueObject, "yyyy-MM-dd");
				}
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("\", $date.toDate(\"yyyy-MM-dd\", \"").append(dbFormat).append("\")))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("ShortFormat\", \"").append(shortFormat).append("\"))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("DBFormat\", \"").append(dbFormat).append("\"))\n");
			} else if (field.getFieldType().equals(Field.FieldType.TIME.toString())) {
				String shortFormat = "";
				if (contFieldValueObject != null && contFieldValueObject instanceof Date) {
					shortFormat = UtilMethods.dateToHTMLDate((Date) contFieldValueObject, "H:mm:ss");
				}
				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("\", $date.toDate(\"H:mm:ss\", \"").append(shortFormat).append("\")))\n");
				sb.append("#set($_dummy = $!dotcms_content_").append( content.getIdentifier()).append(".put(\"").append(field.getVelocityVarName()).append("ShortFormat\", \"").append(shortFormat).append("\"))\n");

			} else if (field.getFieldType().equals(Field.FieldType.DATE_TIME.toString())) {
				String shortFormat = "";
				String longFormat = "";
				String dbFormat = "";
				if (contFieldValueObject != null && contFieldValueObject instanceof Date) {
					shortFormat = UtilMethods.dateToHTMLDate((Date) contFieldValueObject, "MM/dd/yyyy");
					longFormat = UtilMethods.dateToHTMLDate((Date) contFieldValueObject, "MM/dd/yyyy H:mm:ss");
					dbFormat = UtilMethods.dateToHTMLDate((Date) contFieldValueObject, "yyyy-MM-dd H:mm:ss");
				}

				sb.append("#set($_dummy = $!dotcms_content_").append(content.getIdentifier() ).append( ".put(\"" ).append( field.getVelocityVarName() ).append( "\", $date.toDate(\"yyyy-MM-dd H:mm:ss\", \"" ).append( dbFormat ).append( "\")))\n");
				sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"" ).append( field.getVelocityVarName() ).append( "ShortFormat\", \"" ).append( shortFormat ).append( "\"))\n");
				sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"" ).append( field.getVelocityVarName() ).append( "DBFormat\", \"" ).append( dbFormat ).append( "\"))\n");
				sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"" ).append( field.getVelocityVarName() ).append( "LongFormat\", \"" ).append( longFormat ).append( "\"))\n");

			} else if (field.getFieldType().equals(Field.FieldType.BUTTON.toString())) {
				sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"" ).append( field.getVelocityVarName() ).append( "ButtonValue\", \"" ).append( (field.getFieldName() == null ? "" : field.getFieldName())
						).append( "\"))\n");
				sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"" ).append( field.getVelocityVarName() ).append( "ButtonCode\", \"" ).append( (field.getValues() == null ? "" : field.getValues()) ).append( "\"))\n");

			} else if (field.getFieldType().equals(Field.FieldType.CATEGORY.toString())) {

				// Get the Category Field
				Category category = categoryAPI.find(field.getValues(), systemUser, false);

				// Get all the Contentlets Categories
				List<Category> selectedCategories = categoryAPI.getParents(content, systemUser, false);

				// Initialize variables
				String catInodes = "";
				Set<Category> categoryList = new HashSet<Category>();
				List<Category> categoryTree = categoryAPI.getAllChildren(category, systemUser, false);

				if (selectedCategories.size() > 0 && categoryTree != null) {
					for (int k = 0; k < categoryTree.size(); k++) {
						Category cat = (Category) categoryTree.get(k);
						for (Category categ : selectedCategories) {
							if (categ.getInode().equalsIgnoreCase(cat.getInode())) {
								categoryList.add(cat);
							}
						}
					}
				}

				if (categoryList.size() > 0) {
					Iterator<Category> it = categoryList.iterator();
					StringBuilder catbuilder=new StringBuilder();
					while (it.hasNext()) {
						Category cat = (Category) it.next();
						catbuilder.append("\"").append(cat.getInode()).append("\"") ;
						if (it.hasNext()) {
							catbuilder.append(",");
						}
					}
					catInodes=catbuilder.toString();
				}

				sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategories = $categories.filterCategoriesByUserPermissions([" ).append( catInodes ).append( "] ))\n");

				sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesInodes = $contents.getEmptyList())\n");
				sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesNames = $contents.getEmptyList())\n");
				sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesKeys = $contents.getEmptyList())\n");
				sb.append("#foreach ($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategory in $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategories)\n");
				sb.append("#set($_dummy = $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesInodes.add($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategory.inode))\n");
				sb.append("#set($_dummy = $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesNames.add($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategory.categoryName))\n");
				sb.append("#if ($UtilMethods.isSet($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategory.key))\n");
				sb.append("#set($_dummy = $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesKeys.add($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategory.key))\n");
				sb.append("#else\n");
				sb.append("#set($_dummy = $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesKeys.add(''))\n");
				sb.append("#end\n");
				sb.append("#end\n");

				sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"" ).append( field.getVelocityVarName() ).append( "CategoryObjects\", $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategories ))\n");
				sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"" ).append( field.getVelocityVarName() ).append( "Categories\", $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesInodes ))\n");
				//http://jira.dotmarketing.net/browse/DOTCMS-2288
				sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"" ).append( field.getVelocityVarName() ).append( "\", $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesInodes ))\n");
				sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"" ).append( field.getVelocityVarName() ).append( "CategoriesNames\", $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesNames ))\n");
				sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"" ).append( field.getVelocityVarName() ).append( "CategoriesKeys\", $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesKeys ))\n");

				sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategories = $contents.getEmptyList())\n");
				sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesInodes = $contents.getEmptyList())\n");
				sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesNames = $contents.getEmptyList())\n");
				sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesKeys = $contents.getEmptyList())\n");
			}

		}

		// get the contentlet categories to make a list
		String categories = "";
		Set<Category> categoryList = new HashSet<Category>(categoryAPI.getParents(content, systemUser, false));
		if (categoryList != null) {
		    StringBuilder catbuilder=new StringBuilder();
			Iterator<Category> it = categoryList.iterator();
			while (it.hasNext()) {
				Category category = it.next();
				catbuilder.append("\"").append(category.getInode()).append("\"");
				if (it.hasNext()) {
					catbuilder.append(",");
				}
			}
			categories=catbuilder.toString();
		}

		// sets the categories as a list on velocity
		sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategories = $categories.filterCategoriesByUserPermissions([" ).append( categories ).append( "] ))\n");

		sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesInodes = $contents.getEmptyList())\n");
		sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesNames = $contents.getEmptyList())\n");
		sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesKeys = $contents.getEmptyList())\n");
		sb.append("#foreach ($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategory in $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategories)\n");
		sb.append("#set($_dummy = $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesInodes.add($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategory.inode))\n");
		sb.append("#set($_dummy = $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesNames.add($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategory.categoryName))\n");
		sb.append("#if ($UtilMethods.isSet($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategory.key))\n");
		sb.append("#set($_dummy = $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesKeys.add($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategory.key))\n");
		sb.append("#else\n");
		sb.append("#set($_dummy = $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesKeys.add(''))\n");
		sb.append("#end\n");
		sb.append("#end\n");

		sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"ContentletCategoryObjects\", $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategories ))\n");
		sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"contentletCategoryObjects\", $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategories ))\n");
		sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"ContentletCategories\", $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesInodes ))\n");
		sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"contentletCategories\", $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesInodes ))\n");
		sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"ContentletCategoriesNames\", $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesNames ))\n");
		sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"contentletCategoriesNames\", $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesNames ))\n");
		sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"ContentletCategoriesKeys\", $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesKeys ))\n");
		sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"contentletCategoriesKeys\", $dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesKeys ))\n");

		sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategories = $contents.getEmptyList())\n");
		sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesInodes = $contents.getEmptyList())\n");
		sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesNames = $contents.getEmptyList())\n");
		sb.append("#set($dotcms_content_" ).append( content.getIdentifier() ).append( "_filteredCategoriesKeys = $contents.getEmptyList())\n");
//		Was put in to fix DOTCMS-995 but it caused DOTCMS-1210.
//      I actually think it should be fine passed the ctx which is a chained context here
//		sb.append("#set($velocityContext = $UtilMethods.popVelocityContext($velocityContext))\n");

		sb.append(widgetCode);

		if(structure.getStructureType() == Structure.STRUCTURE_TYPE_WIDGET){
			sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"isWidget\", \"" ).append( true ).append( "\"  ))\n");
			if(structure.getName().equals(FormAPI.FORM_WIDGET_STRUCTURE_NAME_FIELD_NAME)){
				sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"isFormWidget\", \"" ).append( true ).append( "\"  ))\n");
			}
		}else{
			sb.append("#set($_dummy = $!dotcms_content_" ).append( content.getIdentifier() ).append( ".put(\"isWidget\", \"" ).append( false ).append( "\"  ))\n");
		}

		sb.append("#set ($content = $dotcms_content_" ).append( content.getIdentifier() ).append( ")");

		if(Config.getBooleanProperty("SHOW_VELOCITYFILES", false)){
			try {

				String velocityRootPath = Config.getStringProperty("VELOCITY_ROOT");

				if (velocityRootPath.startsWith("/WEB-INF")) {
					velocityRootPath = Config.CONTEXT.getRealPath(velocityRootPath);
				}
				velocityRootPath += java.io.File.separator;

				String veloExt = Config.getStringProperty("VELOCITY_CONTENT_MAP_EXTENSION");
				String baseFilename = String.format("%s_%d.%s", content.getIdentifier(), content.getLanguageId(), veloExt);

				// Save always to working
				String filePath = "working" + java.io.File.separator + baseFilename;
				saveToDisk(ConfigUtils.getDynamicVelocityPath()+java.io.File.separator,filePath, sb.toString());

				// Save to live, if publishing
				if (!EDIT_MODE) {
					filePath = "live" + java.io.File.separator + baseFilename;
					saveToDisk(ConfigUtils.getDynamicVelocityPath()+java.io.File.separator,filePath, sb.toString());
				}

			} catch (Exception e) {
				Logger.error(ContentletMapServices.class, e.toString(), e);
			}
		}
		try {
			result = new ByteArrayInputStream(sb.toString().getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e1) {
			result = new ByteArrayInputStream(sb.toString().getBytes());
			Logger.error(ContainerServices.class,e1.getMessage(), e1);
		}
        return result;

	}

	public static void unpublishContentletMapFile(Contentlet asset) throws DotDataException {

		Identifier identifier = APILocator.getIdentifierAPI().find(asset);
		removeContentletMapFile(asset, identifier, false);
	}

	/**
	 * Will remove all contentlet map files within a structure for both live and working. Uses the system user.
	 * @param contentlets
	 * @throws DotSecurityException
	 * @throws DotDataException
	 */
	public static void removeContentletMapFile(Structure structure) throws DotDataException, DotSecurityException{
		ContentletAPI conAPI = APILocator.getContentletAPI();
		int limit = 500;
		int offset = 0;
		List<Contentlet> contentlets = conAPI.findByStructure(structure, APILocator.getUserAPI().getSystemUser(), false, limit, offset);
		int size = contentlets.size();
		while(size > 0){
			for (Contentlet contentlet : contentlets) {
				removeContentletMapFile(contentlet);
			}
			offset += limit;
			contentlets = conAPI.findByStructure(structure, APILocator.getUserAPI().getSystemUser(), false, limit, offset);
			size = contentlets.size();
		}
	}

	/**
	 * Will remove all contentlet map files for both live and working
	 * @param contentlets
	 * @throws DotDataException
	 */
	public static void removeContentletMapFile(Contentlet contentlet) throws DotDataException{
		removeContentletMapFile(contentlet, true);
		removeContentletMapFile(contentlet, false);
	}

	/**
	 * Will remove all contentlet map files for both live and working
	 * @param contentlets
	 */
	public static void removeContentletMapFile(List<Contentlet> contentlets) throws DotDataException{
		for (Contentlet contentlet : contentlets) {
			removeContentletMapFile(contentlet);
		}
	}

	public static void removeContentletMapFile(Contentlet asset, boolean EDIT_MODE) throws DotDataException {

		Identifier identifier = APILocator.getIdentifierAPI().find(asset);
		removeContentletMapFile(asset, identifier, EDIT_MODE);
	}

	public static void removeContentletMapFile(Contentlet asset, Identifier identifier, boolean EDIT_MODE) {
		String folderPath = (!EDIT_MODE) ? "live/" : "working/";
		String velocityRoot = Config.CONTEXT.getRealPath("/WEB-INF/velocity/") + folderPath;
		String filePath=  folderPath + identifier.getInode() + "_" + asset.getLanguageId() + "." + Config.getStringProperty("VELOCITY_CONTENT_MAP_EXTENSION");
		java.io.File f = new java.io.File (velocityRoot + filePath);
		f.delete();
		DotResourceCache vc = CacheLocator.getVeloctyResourceCache();
        vc.remove(ResourceManager.RESOURCE_TEMPLATE + filePath );
        List<Field> fields = FieldsCache.getFieldsByStructureInode(asset.getStructureInode());
        for (Field field : fields) {
			try {
				FieldServices.invalidate(field.getInode(), asset.getInode(), EDIT_MODE);
			} catch (DotDataException e) {
				Logger.error(ContentletServices.class,e.getMessage(),e);
			} catch (DotSecurityException e) {
				Logger.error(ContentletServices.class,e.getMessage(),e);
			}
		}
	}

	/**
	 * Returns the details page URI for a given <tt>contentlet</tt>. (Used by
	 * #detailPageLink macro)
	 *
	 * @param the
	 *            given <tt>contentlet</tt>
	 * @return the details page URI
	 *
	 * @author Dimitris Zavaliadis
	 * @version 1.0
	 */
	private static String getDetailPageURI(Contentlet contentlet) {
		String detailPageURI = null;
		Structure structure = contentlet.getStructure();
		String detailPageId = structure.getDetailPage();
		try {
			Identifier pageIdentifier = APILocator.getIdentifierAPI().find(detailPageId);
			if (!InodeUtils.isSet(pageIdentifier.getInode()) && UtilMethods.isSet(detailPageId)) {
				pageIdentifier = APILocator.getIdentifierAPI().find((HTMLPage) InodeFactory.getInode(detailPageId, HTMLPage.class));
			}
			detailPageURI = pageIdentifier.getURI();
		} catch (Exception e) {
			Logger.error(ContentletMapServices.class, e.getMessage());
		}
		return detailPageURI;
	}

	private static void saveToDisk(String folderPath, String filePath, String data) throws IOException {

		java.io.BufferedOutputStream tmpOut = new java.io.BufferedOutputStream(new java.io.FileOutputStream(new java.io.File(folderPath+ filePath)));

		// Specify a proper character encoding
		OutputStreamWriter out = new OutputStreamWriter(tmpOut, UtilMethods.getCharsetConfiguration());

		out.write(data);

		out.flush();
		out.close();
		tmpOut.close();
		DotResourceCache vc = CacheLocator.getVeloctyResourceCache();
        vc.remove(ResourceManager.RESOURCE_TEMPLATE + filePath );

	}

}