# WWW SQL Designer

[![WWW SQL Designer](https://raw.githubusercontent.com/nidorx/wwwsqldesigner/master/src/web/assets/images/screen.png)]

WWW SQL Designer allows users to create database designs, which can be saved/loaded and exported to SQL scripts. Various databases and languages are supported. Ability to import existing database design.

[[YouTube video](http://www.youtube.com/watch?v=hCQzJx9AKhU)]

# About

This tool allows you to draw and create database schemas (E-R diagrams) directly in browser, without the need for any external programs (flash). You only need JavaScript enabled.
The Designer works perfectly in Mozillas (Firefox, Seamonkey), Internet Explorers (6, 7, 8), Safari and Operas. Konqueror works, but the experience is limited.

Many database features are supported, such as keys, foreign key constraints, comments and indexes. You can either save your design (for further loading & modifications), print it or export as SQL script. It is possible to retrieve (import) schema from existing database.

WWW SQL Designer was created by [Ondrej Zara](http://ondras.zarovi.cz/) and is built atop the [oz.js](http://code.google.com/p/oz-js/) JavaScript module. It is distributed under New BSD license.

If you wish to support this project, <a href='https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=3340079'><img src='https://www.paypal.com/en_GB/i/btn/btn_donate_LG.gif' alt='Donate at PayPal' title='Donate at PayPal' /></a> at PayPal!




# Build


javac -d ./build ./src/server/*.java
jar cvmf ./src/server/MANIFEST.MF ./dist/www-sql-designer-3.0.0.jar -C build/ . -C src/ web/


#Run

## Windows
java -jar ./dist/www-sql-designer-3.0.0.jar