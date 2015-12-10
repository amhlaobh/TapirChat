#!/bin/sh
set -o errexit
set -o nounset

cygwin=0
if uname -o | grep -i cygwin ; then cygwin=1 ; fi

# modify these:
project=tc
compile=1
tmpclasses="tmp_classes" # set to "bin" if wished

# no modifications after here:

if [ ! -d src ] ; then
    echo "No src directory"
    exit 1
fi

today=`date +%Y%m%d`
jarfile=${project}-${today}.jar
srcfile=${project}-src-${today}.jar
clientmf=swingui.mf
servermf=hub.mf
clientjarfile=${project}-${today}-client.jar
serverjarfile=${project}-${today}-server.jar
latestClientJar=${project}-latest-client.jar
bindir=bin
jarsdir=jars
exitAfterCompile=0
doClean=0
doJarClean=0
doDistClean=0
doArchive=1
if [ "$#" != 0 ] ; then
    if  [ "$1" == "-h" ] ; then
        echo "build.sh -c          compile only"
        echo "build.sh -clean      clean temporary files"
        echo "build.sh -jarclean  clean jars"
        echo "build.sh -distclean  clean everything but src files"
        echo "build.sh -noarchive  DON'T move built jar files to ${jarsdir}"
        exit 0
    fi
    if  [ "$1" == "-c" ] ; then
        exitAfterCompile=1
    fi
    if  [ "$1" == "-clean" ] ; then
        doClean=1
    fi
    if  [ "$1" == "-jarclean" ] ; then
        doJarClean=1
    fi
    if  [ "$1" == "-distclean" ] ; then
        doDistClean=1
    fi
    if  [ "$1" == "-noarchive" ] ; then
        doArchive=0
    fi
fi
here=`pwd`
cd $here

if [ "${doDistClean}" == "1" ] ; then
    doClean=1
    doJarClean=1
    echo "dist cleaning"
    echo "cleaning BUILD*"
    rm -rf BUILD-* BUILDNO
fi

if [ "${doClean}" == "1" ] ; then
    echo "cleaning dist"
    rm -rf dist
    echo "cleaning dist-src"
    rm -rf dist-src
    echo "cleaning ${tmpclasses}"
    rm -rf ${tmpclasses}
    echo "cleaning sources_list.txt"
    rm -rf  sources_list.txt
fi

if [ "${doJarClean}" == "1" ] ; then
    echo "cleaning jars"
    rm -rf ${project}-src*.jar 
    echo "cleaning source jars  "
    rm -rf ${project}-src*.jar 
fi

if [ "${doDistClean}" == "1" ] || [ "${doClean}" == "1" ] || [ "${doJarClean}" == "1" ] ; then
    exit 0
fi

# also compile, don't take class files from eclipse
echo "compile"
if [ "${compile}" == "1" ] ; then
    find ./src -name *.java > sources_list.txt
    if [ -d ${tmpclasses} ] ; then rm -rf ${tmpclasses} ; fi
    mkdir ${tmpclasses}
    javac -Xlint:deprecation -d ${tmpclasses} -g  @sources_list.txt

    bindir=${tmpclasses}

fi
if [ "${exitAfterCompile}" == "1" ] ; then
    echo "Compile only, exit."
    exit 0
fi

# determine build number
if [ ! -f BUILDNO ] ; then
    echo "1" > BUILDNO
    i=1
else
    i=`cat BUILDNO`
    if [ -f BUILD-${i} ] ; then
        rm BUILD-*
    fi
    (( i ++ ))
fi
echo $i > BUILDNO
touch BUILD-${i}


echo "Build $i"

echo packing bin
cd $here
if [ -d dist ] ; then rm -rf dist ; fi
mkdir dist
rsync -a ${bindir}/ dist
cp -p build.sh BUILDNO BUILD-* dist
cp -p manifest.mf dist
if [ -f Changelog ] ; then cp -p Changelog dist ; fi
cd dist
if [ ${cygwin} == 1 ] ; then
    jar -cfm `cygpath -w ${here}/${jarfile}` manifest.mf .
else
    jar -cfm ${here}/${jarfile} manifest.mf .
fi

echo packing src
cd $here
if [ -d dist-src ] ; then rm -rf dist-src ; fi
mkdir dist-src
rsync -a src dist-src
for i in  TODO Changelog ; do if [ -f "${i}" ] ; then cp -p "${i}" dist-src ; fi ; done
cp -p manifest.mf build.sh dist-src
if [ -f Changelog ] ; then cp -p Changelog dist-src ; fi
if [ ${cygwin} == 1 ] ; then
   jar -cf `cygpath -w ${here}/${srcfile}` dist-src
else
   jar -cf ${here}/${srcfile} dist-src
fi

# -- custom packaging

echo packing client bin
cd $here
d=dist-client
if [ -d ${d} ] ; then rm -rf ${d} ; fi
mkdir ${d}
f="Logger*.class Message*class Options*class SwingUI*class TextUI*class LoadUI*class Tray*class ObjectCrypter*class"
files=""
for file in $f; do files="${files} --include=$file" ; done
rsync -a ${bindir}/ ${files} --exclude="*.class" ${d}
rsync -a img ${d}
rsync -a ${clientmf} ${d}
if [ -f Changelog ] ; then cp -p Changelog ${d} ; fi
cd ${d}
echo creating ${clientjarfile}
if [ ${cygwin} == 1 ] ; then
   jar -cfm `cygpath -w ${here}/${clientjarfile}` ${clientmf} .
else
   jar -cfm ${here}/${clientjarfile} ${clientmf} .
fi
if [ -f ${here}/${latestClientJar} ] ; then rm ${here}/${latestClientJar} ; fi
cp -p  ${here}/${clientjarfile} ${here}/${latestClientJar}

echo packing server bin
cd $here
d=dist-client
if [ -d ${d} ] ; then rm -rf ${d} ; fi
mkdir ${d}
f="Logger*.class Message*class Options*class Hub*class Client*class Tray*class"
files=""
for file in $f; do files="${files} --include=$file" ; done
rsync -a ${bindir}/ ${files} --exclude="*.class" ${d}
rsync -a img ${d}
rsync -a ${servermf} ${d}
if [ -f Changelog ] ; then cp -p Changelog ${d} ; fi
cd ${d}
echo creating ${serverjarfile}
if [ ${cygwin} == 1 ] ; then
   jar -cfm `cygpath -w ${here}/${serverjarfile}` ${servermf} .
else
   jar -cfm ${here}/${serverjarfile} ${servermf} .
fi
# -- custom packaging end

if [ "${doArchive}" = "1" ] ; then
    if [ ! -d ${jarsdir} ] ; then mkdir ${here}/${jarsdir} ; fi
    mv ${here}/${serverjarfile}  ${here}/${jarsdir}
    mv ${here}/${clientjarfile}  ${here}/${jarsdir}
    mv ${here}/${jarfile}  ${here}/${jarsdir}
    mv ${here}/${srcfile} ${here}/${jarsdir}
fi
