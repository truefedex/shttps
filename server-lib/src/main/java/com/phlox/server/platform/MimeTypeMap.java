package com.phlox.server.platform;

import java.util.HashMap;
import java.util.Locale;

public class MimeTypeMap {

    private static final class InstanceHolder {
        static final MimeTypeMap instance = new MimeTypeMap();
    }

    public static MimeTypeMap getInstance() {
        return InstanceHolder.instance;
    }

    private static final String[] MIME_TYPE_MAP_ARRAY = {
            "application/andrew-inset", "ez",
        "application/dsptype", "tsp",
        "application/futuresplash", "spl",
        "application/hta", "hta",
        "application/mac-binhex40", "hqx",
        "application/mac-compactpro", "cpt",
        "application/mathematica", "nb",
        "application/msaccess", "mdb",
        "application/oda", "oda",
        "application/ogg", "ogg",
        "application/pdf", "pdf",
        "application/pgp-keys", "key",
        "application/pgp-signature", "pgp",
        "application/pics-rules", "prf",
        "application/rar", "rar",
        "application/rdf+xml", "rdf",
        "application/rss+xml", "rss",
        "application/zip", "zip",
        "application/vnd.android.package-archive",
                "apk",
        "application/vnd.cinderella", "cdy",
        "application/vnd.ms-pki.stl", "stl",
        
        "application/vnd.oasis.opendocument.database", "odb",

        "application/vnd.oasis.opendocument.formula", "odf",

        "application/vnd.oasis.opendocument.graphics", "odg",

        "application/vnd.oasis.opendocument.graphics-template",
        "otg",

        "application/vnd.oasis.opendocument.image", "odi",

        "application/vnd.oasis.opendocument.spreadsheet", "ods",

        "application/vnd.oasis.opendocument.spreadsheet-template",
        "ots",

        "application/vnd.oasis.opendocument.text", "odt",

        "application/vnd.oasis.opendocument.text-master", "odm",

        "application/vnd.oasis.opendocument.text-template", "ott",

        "application/vnd.oasis.opendocument.text-web", "oth",
        "application/msword", "doc",
        "application/msword", "dot",
        
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "docx",

        "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "dotx",
        "application/vnd.ms-excel", "xls",
        "application/vnd.ms-excel", "xlt",
        
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xlsx",

        "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        "xltx",
        "application/vnd.ms-powerpoint", "ppt",
        "application/vnd.ms-powerpoint", "pot",
        "application/vnd.ms-powerpoint", "pps",
        
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "pptx",

        "application/vnd.openxmlformats-officedocument.presentationml.template",
        "potx",

        "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "ppsx",
        "application/vnd.rim.cod", "cod",
        "application/vnd.smaf", "mmf",
        "application/vnd.stardivision.calc", "sdc",
        "application/vnd.stardivision.draw", "sda",
        
        "application/vnd.stardivision.impress", "sdd",

        "application/vnd.stardivision.impress", "sdp",
        "application/vnd.stardivision.math", "smf",
        "application/vnd.stardivision.writer",
                "sdw",
        "application/vnd.stardivision.writer",
                "vor",
        
        "application/vnd.stardivision.writer-global", "sgl",
        "application/vnd.sun.xml.calc", "sxc",
        
        "application/vnd.sun.xml.calc.template", "stc",
        "application/vnd.sun.xml.draw", "sxd",
        
        "application/vnd.sun.xml.draw.template", "std",
        "application/vnd.sun.xml.impress", "sxi",
        
        "application/vnd.sun.xml.impress.template", "sti",
        "application/vnd.sun.xml.math", "sxm",
        "application/vnd.sun.xml.writer", "sxw",
        
        "application/vnd.sun.xml.writer.global", "sxg",

        "application/vnd.sun.xml.writer.template", "stw",
        "application/vnd.visio", "vsd",
        "application/x-abiword", "abw",
        "application/x-apple-diskimage", "dmg",
        "application/x-bcpio", "bcpio",
        "application/x-bittorrent", "torrent",
        "application/x-cdf", "cdf",
        "application/x-cdlink", "vcd",
        "application/x-chess-pgn", "pgn",
        "application/x-cpio", "cpio",
        "application/x-debian-package", "deb",
        "application/x-debian-package", "udeb",
        "application/x-director", "dcr",
        "application/x-director", "dir",
        "application/x-director", "dxr",
        "application/x-dms", "dms",
        "application/x-doom", "wad",
        "application/x-dvi", "dvi",
        "application/x-flac", "flac",
        "application/x-font", "pfa",
        "application/x-font", "pfb",
        "application/x-font", "gsf",
        "application/x-font", "pcf",
        "application/x-font", "pcf.Z",
        "application/x-freemind", "mm",
        "application/x-futuresplash", "spl",
        "application/x-gnumeric", "gnumeric",
        "application/x-go-sgf", "sgf",
        "application/x-graphing-calculator", "gcf",
        "application/x-gtar", "gtar",
        "application/x-gtar", "tgz",
        "application/x-gtar", "taz",
        "application/x-hdf", "hdf",
        "application/x-ica", "ica",
        "application/x-internet-signup", "ins",
        "application/x-internet-signup", "isp",
        "application/x-iphone", "iii",
        "application/x-iso9660-image", "iso",
        "application/x-jmol", "jmz",
        "application/x-kchart", "chrt",
        "application/x-killustrator", "kil",
        "application/x-koan", "skp",
        "application/x-koan", "skd",
        "application/x-koan", "skt",
        "application/x-koan", "skm",
        "application/x-kpresenter", "kpr",
        "application/x-kpresenter", "kpt",
        "application/x-kspread", "ksp",
        "application/x-kword", "kwd",
        "application/x-kword", "kwt",
        "application/x-latex", "latex",
        "application/x-lha", "lha",
        "application/x-lzh", "lzh",
        "application/x-lzx", "lzx",
        "application/x-maker", "frm",
        "application/x-maker", "maker",
        "application/x-maker", "frame",
        "application/x-maker", "fb",
        "application/x-maker", "book",
        "application/x-maker", "fbdoc",
        "application/x-mif", "mif",
        "application/x-ms-wmd", "wmd",
        "application/x-ms-wmz", "wmz",
        "application/x-msi", "msi",
        "application/x-ns-proxy-autoconfig", "pac",
        "application/x-nwc", "nwc",
        "application/x-object", "o",
        "application/x-oz-application", "oza",
        "application/x-pkcs12", "p12",
        "application/x-pkcs7-certreqresp", "p7r",
        "application/x-pkcs7-crl", "crl",
        "application/x-quicktimeplayer", "qtl",
        "application/x-shar", "shar",
        "application/x-shockwave-flash", "swf",
        "application/x-stuffit", "sit",
        "application/x-sv4cpio", "sv4cpio",
        "application/x-sv4crc", "sv4crc",
        "application/x-tar", "tar",
        "application/x-texinfo", "texinfo",
        "application/x-texinfo", "texi",
        "application/x-troff", "t",
        "application/x-troff", "roff",
        "application/x-troff-man", "man",
        "application/x-ustar", "ustar",
        "application/x-wais-source", "src",
        "application/x-wingz", "wz",
        "application/x-webarchive", "webarchive",
        "application/x-x509-ca-cert", "crt",
        "application/x-x509-user-cert", "crt",
        "application/x-xcf", "xcf",
        "application/x-xfig", "fig",
        "application/xhtml+xml", "xhtml",
        "audio/3gpp", "3gpp",
        "audio/basic", "snd",
        "audio/midi", "mid",
        "audio/midi", "midi",
        "audio/midi", "kar",
        "audio/mpeg", "mpga",
        "audio/mpeg", "mpega",
        "audio/mpeg", "mp2",
        "audio/mpeg", "mp3",
        "audio/mpeg", "m4a",
        "audio/mpegurl", "m3u",
        "audio/prs.sid", "sid",
        "audio/x-aiff", "aif",
        "audio/x-aiff", "aiff",
        "audio/x-aiff", "aifc",
        "audio/x-gsm", "gsm",
        "audio/x-mpegurl", "m3u",
        "audio/x-ms-wma", "wma",
        "audio/x-ms-wax", "wax",
        "audio/x-pn-realaudio", "ra",
        "audio/x-pn-realaudio", "rm",
        "audio/x-pn-realaudio", "ram",
        "audio/x-realaudio", "ra",
        "audio/x-scpls", "pls",
        "audio/x-sd2", "sd2",
        "audio/x-wav", "wav",
        "image/bmp", "bmp",
        "image/gif", "gif",
        "image/ico", "cur",
        "image/ico", "ico",
        "image/ief", "ief",
        "image/jpeg", "jpeg",
        "image/jpeg", "jpg",
        "image/jpeg", "jpe",
        "image/pcx", "pcx",
        "image/png", "png",
        "image/svg+xml", "svg",
        "image/svg+xml", "svgz",
        "image/tiff", "tiff",
        "image/tiff", "tif",
        "image/vnd.djvu", "djvu",
        "image/vnd.djvu", "djv",
        "image/vnd.wap.wbmp", "wbmp",
        "image/x-cmu-raster", "ras",
        "image/x-coreldraw", "cdr",
        "image/x-coreldrawpattern", "pat",
        "image/x-coreldrawtemplate", "cdt",
        "image/x-corelphotopaint", "cpt",
        "image/x-icon", "ico",
        "image/x-jg", "art",
        "image/x-jng", "jng",
        "image/x-ms-bmp", "bmp",
        "image/x-photoshop", "psd",
        "image/x-portable-anymap", "pnm",
        "image/x-portable-bitmap", "pbm",
        "image/x-portable-graymap", "pgm",
        "image/x-portable-pixmap", "ppm",
        "image/x-rgb", "rgb",
        "image/x-xbitmap", "xbm",
        "image/x-xpixmap", "xpm",
        "image/x-xwindowdump", "xwd",
        "model/iges", "igs",
        "model/iges", "iges",
        "model/mesh", "msh",
        "model/mesh", "mesh",
        "model/mesh", "silo",
        "text/calendar", "ics",
        "text/calendar", "icz",
        "text/comma-separated-values", "csv",
        "text/css", "css",
        "text/html", "htm",
        "text/html", "html",
        "text/h323", "323",
        "text/iuls", "uls",
        "text/mathml", "mml",
        // add it first so it will be the default for ExtensionFromMimeType
        "text/plain", "txt",
        "text/plain", "asc",
        "text/plain", "text",
        "text/plain", "diff",
        "text/plain", "po",     // reserve "pot" for vnd.ms-powerpoint
        "text/richtext", "rtx",
        "text/rtf", "rtf",
        "text/texmacs", "ts",
        "text/text", "phps",
        "text/tab-separated-values", "tsv",
        "text/xml", "xml",
        "text/x-bibtex", "bib",
        "text/x-boo", "boo",
        "text/x-c++hdr", "h++",
        "text/x-c++hdr", "hpp",
        "text/x-c++hdr", "hxx",
        "text/x-c++hdr", "hh",
        "text/x-c++src", "c++",
        "text/x-c++src", "cpp",
        "text/x-c++src", "cxx",
        "text/x-chdr", "h",
        "text/x-component", "htc",
        "text/x-csh", "csh",
        "text/x-csrc", "c",
        "text/x-dsrc", "d",
        "text/x-haskell", "hs",
        "text/x-java", "java",
        "text/x-literate-haskell", "lhs",
        "text/x-moc", "moc",
        "text/x-pascal", "p",
        "text/x-pascal", "pas",
        "text/x-pcs-gcd", "gcd",
        "text/x-setext", "etx",
        "text/x-tcl", "tcl",
        "text/x-tex", "tex",
        "text/x-tex", "ltx",
        "text/x-tex", "sty",
        "text/x-tex", "cls",
        "text/x-vcalendar", "vcs",
        "text/x-vcard", "vcf",
        "video/3gpp", "3gpp",
        "video/3gpp", "3gp",
        "video/3gpp", "3g2",
        "video/dl", "dl",
        "video/dv", "dif",
        "video/dv", "dv",
        "video/fli", "fli",
        "video/m4v", "m4v",
        "video/mpeg", "mpeg",
        "video/mpeg", "mpg",
        "video/mpeg", "mpe",
        "video/mp4", "mp4",
        "video/mpeg", "VOB",
        "video/quicktime", "qt",
        "video/quicktime", "mov",
        "video/vnd.mpegurl", "mxu",
        "video/x-la-asf", "lsf",
        "video/x-la-asf", "lsx",
        "video/x-mng", "mng",
        "video/x-ms-asf", "asf",
        "video/x-ms-asf", "asx",
        "video/x-ms-wm", "wm",
        "video/x-ms-wmv", "wmv",
        "video/x-ms-wmx", "wmx",
        "video/x-ms-wvx", "wvx",
        "video/x-msvideo", "avi",
        "video/x-sgi-movie", "movie",
        "x-conference/x-cooltalk", "ice",
        "x-epoc/x-sisx-app", "sisx",

        "text/javascript" , "js",
        "application/json" , "json"
    };

    private final HashMap<String, String> EXTENSION_TO_MIME_TYPE_MAP = new HashMap<>();

    private MimeTypeMap() {
        for (int i = 0; i < MIME_TYPE_MAP_ARRAY.length; i += 2) {
            EXTENSION_TO_MIME_TYPE_MAP.put(MIME_TYPE_MAP_ARRAY[i + 1], MIME_TYPE_MAP_ARRAY[i]);
        }
    }

    public String getFileExtensionFromUrl(String url) {
        if (url == null) {
            return null;
        }

        int fragment = url.lastIndexOf('#');
        if (fragment > 0) {
            url = url.substring(0, fragment);
        }

        int query = url.lastIndexOf('?');
        if (query > 0) {
            url = url.substring(0, query);
        }

        int filenamePos = url.lastIndexOf('/');
        String filename =
                0 <= filenamePos ? url.substring(filenamePos + 1) : url;

        if (!filename.isEmpty()) {
            int dotPos = filename.lastIndexOf('.');
            if (0 <= dotPos) {
                return filename.substring(dotPos + 1);
            }
        }

        return null;
    }

    public String getMimeTypeFromExtension(String extension) {
        if (extension == null) {
            return null;
        }

        // Convert the extension to lowercase, as MIME types are case-insensitive
        extension = extension.toLowerCase(Locale.US);

        // Map the extension to a MIME type
        return EXTENSION_TO_MIME_TYPE_MAP.get(extension);
    }
}

