<?xml version="1.0" ?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
    <xsl:output method="text"/>

    <xsl:template name="replace-substring">
        <xsl:param name="value" />
        <xsl:param name="from" />
        <xsl:param name="to" />
        <xsl:choose>
            <xsl:when test="contains($value,$from)">
                <xsl:value-of select="substring-before($value,$from)" />
                <xsl:value-of select="$to" />
                <xsl:call-template name="replace-substring">
                    <xsl:with-param name="value" select="substring-after($value,$from)" />
                    <xsl:with-param name="from" select="$from" />
                    <xsl:with-param name="to" select="$to" />
                </xsl:call-template>
            </xsl:when>
            <xsl:otherwise>
                <xsl:value-of select="$value" />
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

    <xsl:template match="/sql">

        <!-- tables -->
        <xsl:for-each select="table">
            <xsl:variable name="table" select="@name" />
            <xsl:text>CREATE TABLE `</xsl:text>
            <xsl:value-of select="@name" />
            <xsl:text>` (
            </xsl:text>
            <xsl:for-each select="row">
                <xsl:text>`</xsl:text>
                <xsl:value-of select="@name" />
                <xsl:text>` </xsl:text>

                <xsl:value-of select="datatype" />
                <xsl:text> </xsl:text>

                <xsl:if test="@null = 0">
                    <xsl:text>NOT NULL </xsl:text>
                </xsl:if>

                <!-- needs to be fixed using sequences
                <xsl:if test="@autoincrement = 1">
                        <xsl:text>AUTO_INCREMENT </xsl:text>
                </xsl:if>
                -->

                <xsl:if test="default">
                    <xsl:text>DEFAULT </xsl:text>
                    <xsl:value-of select="default" />
                    <xsl:text> </xsl:text>
                </xsl:if>

                <xsl:if test="not (position()=last())">
                    <xsl:text>,
                    </xsl:text>
                </xsl:if>
            </xsl:for-each>

            <!-- keys -->
            <xsl:for-each select="key">
                <xsl:text>,
                </xsl:text>
                <xsl:choose>
                    <xsl:when test="@type = 'PRIMARY'">PRIMARY KEY (</xsl:when>
                    <xsl:when test="@type = 'FULLTEXT'">FULLTEXT KEY (</xsl:when>
                    <xsl:when test="@type = 'UNIQUE'">UNIQUE KEY (</xsl:when>
                    <xsl:otherwise>KEY (</xsl:otherwise>
                </xsl:choose>

                <xsl:for-each select="part">
                    <xsl:text>`</xsl:text>
                    <xsl:value-of select="." />
                    <xsl:text>`</xsl:text>
                    <xsl:if test="not (position() = last())">
                        <xsl:text>, </xsl:text>
                    </xsl:if>
                </xsl:for-each>
                <xsl:text>)</xsl:text>

            </xsl:for-each>

            <xsl:text>
                );
            </xsl:text>

            <xsl:if test="comment">
                <xsl:text>COMMENT ON TABLE `</xsl:text>
                <xsl:value-of select="@name" />
                <xsl:text>` IS '</xsl:text>
                <xsl:call-template name="replace-substring">
                    <xsl:with-param name="value" select="comment" />
                    <xsl:with-param name="from" select='"&apos;"' />
                    <xsl:with-param name="to" select='"&apos;&apos;"' />
                </xsl:call-template>
                <xsl:text>';
                </xsl:text>
            </xsl:if>

            <xsl:for-each select="row">
                <xsl:if test="comment">
                    <xsl:text>COMMENT ON COLUMN `</xsl:text>
                    <xsl:value-of select="$table" />
                    <xsl:text>`.`</xsl:text>
                    <xsl:value-of select="@name" />
                    <xsl:text>` IS '</xsl:text>
                    <xsl:call-template name="replace-substring">
                        <xsl:with-param name="value" select="comment" />
                        <xsl:with-param name="from" select='"&apos;"' />
                        <xsl:with-param name="to" select='"&apos;&apos;"' />
                    </xsl:call-template>
                    <xsl:text>';
                    </xsl:text>
                </xsl:if>
            </xsl:for-each>

        </xsl:for-each>

        <!-- fk -->
        <xsl:for-each select="table">
            <xsl:for-each select="row">
                <xsl:for-each select="relation">
                    <xsl:text>ALTER TABLE `</xsl:text>
                    <xsl:value-of select="../../@name" />
                    <xsl:text>` ADD FOREIGN KEY (</xsl:text>
                    <xsl:value-of select="../@name" />
                    <xsl:text>) REFERENCES `</xsl:text>
                    <xsl:value-of select="@table" />
                    <xsl:text>` (`</xsl:text>
                    <xsl:value-of select="@row" />
                    <xsl:text>`);
                    </xsl:text>
                </xsl:for-each>
            </xsl:for-each>
        </xsl:for-each>

    </xsl:template>
</xsl:stylesheet>