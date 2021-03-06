/* This file is part of VoltDB.
 * Copyright (C) 2008-2014 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.utils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.voltcore.utils.CoreUtils;

import junit.framework.TestCase;

public class TestSqlCommandParserInteractive extends TestCase {

    static ExecutorService executor = CoreUtils.getSingleThreadExecutor("TestSqlCommandParser");

    Callable<List<String>> makeQueryTask(final InputStream in, final OutputStream out)
    {
        return new Callable<List<String>>() {
                public List<String> call() {
                    List<String> results = null;
                    try {
                        SQLConsoleReader reader = new SQLConsoleReader(in, out);
                        SQLCommand.mockLineReaderForTest(reader);
                        results = SQLCommand.getQuery(true);
                    } catch (Exception ioe) {}
                    return results;
                }
        };
    }

    static class CommandStuff
    {
        PipedInputStream pis;
        ByteArrayOutputStream baos;
        PipedOutputStream pos;
        Future<List<String>> result = null;

        CommandStuff()
        {
            pis = new PipedInputStream();
            baos = new ByteArrayOutputStream();
            try {
                pos = new PipedOutputStream(pis);
            } catch (Exception e) {}
        }

        Callable<List<String>> makeQueryTask(final InputStream in, final OutputStream out)
        {
            return new Callable<List<String>>() {
                public List<String> call() {
                    List<String> results = null;
                    try {
                        SQLConsoleReader reader = new SQLConsoleReader(in, out);
                        SQLCommand.mockLineReaderForTest(reader);
                        results = SQLCommand.getQuery(true);
                    } catch (Exception ioe) {}
                    return results;
                }
            };
        }

        public Future<List<String>> openQuery()
        {
            result = executor.submit(makeQueryTask(pis, baos));
            return result;
        }

        public void submitText(String text) throws Exception
        {
            pos.write(text.getBytes(), 0, text.length());
        }

        public void waitOnResult()
        {
            while (!result.isDone()) {
                Thread.yield();
            }
        }

        public Future<List<String>> getResult()
        {
            return result;
        }

        public void close() throws Exception
        {
            pos.close();
        }
    }

    // Verify all the basic single line DML/DQL works
    public void testSimpleQueries() throws Exception
    {
        CommandStuff cmd = new CommandStuff();
        Future<List<String>> result = cmd.openQuery();
        String query = "select * from goats";
        cmd.submitText(query + ";\n");
        cmd.waitOnResult();
        assertEquals(1, result.get().size());
        assertEquals(query, result.get().get(0));
        System.out.println("RESULT: " + result.get());

        result = cmd.openQuery();
        query = "insert into goats values ('chicken', 'cheese')";
        cmd.submitText(query + ";\n");
        cmd.waitOnResult();
        assertEquals(1, result.get().size());
        assertEquals(query, result.get().get(0));
        System.out.println("RESULT: " + result.get());

        result = cmd.openQuery();
        query = "update goats set livestock = 'chicken' where dairy = 'cheese'";
        cmd.submitText(query + ";\n");
        cmd.waitOnResult();
        assertEquals(1, result.get().size());
        assertEquals(query, result.get().get(0));
        System.out.println("RESULT: " + result.get());

        result = cmd.openQuery();
        query = "delete from goats where dairy = 'cheese'";
        cmd.submitText(query + ";\n");
        cmd.waitOnResult();
        assertEquals(1, result.get().size());
        assertEquals(query, result.get().get(0));
        System.out.println("RESULT: " + result.get());
        cmd.close();
    }

    public void testSemicolonSeparation() throws Exception
    {
        CommandStuff cmd = new CommandStuff();
        Future<List<String>> result = cmd.openQuery();
        String query1 = "select * from goats";
        cmd.submitText(query1 + ";");
        Thread.sleep(100);
        assertFalse(result.isDone());
        String query2 = "delete from boats";
        // add some whitespace and extra ;;;
        cmd.submitText(query2 + " ;;  ; ;   ; ");
        Thread.sleep(100);
        assertFalse(result.isDone());
        String query3 = "insert into stoats values (0, 1)";
        cmd.submitText(query3 + ";\n");
        cmd.waitOnResult();
        assertEquals(3, result.get().size());
        assertEquals(query1, result.get().get(0));
        assertEquals(query2, result.get().get(1));
        assertEquals(query3, result.get().get(2));
        cmd.close();
    }

    public void testQuotedSemicolons() throws Exception
    {
        CommandStuff cmd = new CommandStuff();
        Future<List<String>> result = cmd.openQuery();
        cmd.submitText("insert into goats values ('whywouldyoudothis?;', 'Ihateyou!')");
        // despite the semicolon/CR, that query is not finished
        Thread.sleep(100);
        //assertFalse(result.isDone());
        cmd.submitText(";\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
        // carriage return becomes a space in the parser
        assertEquals("insert into goats values ('whywouldyoudothis?;', 'Ihateyou!')",
                result.get().get(0));
        cmd.close();
    }

    public void testComments() throws Exception
    {
        CommandStuff cmd = new CommandStuff();
        Future<List<String>> result = cmd.openQuery();
        // This is the behavior you might want, doesn't work
        //cmd.submitText("--insert into goats values (0, 1)\n");
        //cmd.waitOnResult();
        //System.out.println("RESULT: " + result.get());
        //assertEquals(0, result.get().size());
        //result = cmd.openQuery();

        cmd.submitText("--insert into goats values (0, 1)");
        Thread.sleep(100);
        assertFalse(result.isDone());
        cmd.submitText("; select * from goats;\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(0, result.get().size());

        result = cmd.openQuery();
        cmd.submitText("insert into goats values (0, 1)");
        Thread.sleep(100);
        assertFalse(result.isDone());
        cmd.submitText("; --select * from goats;\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(2, result.get().size());
        assertEquals("insert into goats values (0, 1)", result.get().get(0));

        cmd.close();
    }

    public void testUnionStatement() throws Exception
    {
        CommandStuff cmd = new CommandStuff();
        Future<List<String>> result = cmd.openQuery();
        String query = "select * from goats union select * from chickens";
        cmd.submitText(query + ";\n");
        cmd.waitOnResult();
        assertEquals(1, result.get().size());
        assertEquals(query, result.get().get(0));
        System.out.println("RESULT: " + result.get());
        cmd.close();
    }

    public void testCreateTable() throws Exception
    {
        CommandStuff cmd = new CommandStuff();
        Future<List<String>> result = cmd.openQuery();
        String create = "create table foo (col1 integer, col2 varchar(50) default ';')";
        cmd.submitText(create + ";\n");
        cmd.waitOnResult();
        assertEquals(1, result.get().size());
        assertEquals(create, result.get().get(0));
    }

    public void testMultiLineCreate() throws Exception
    {
        CommandStuff cmd = new CommandStuff();
        Future<List<String>> result = cmd.openQuery();
        cmd.submitText("create table foo (\n");
        Thread.sleep(100);
        assertFalse(result.isDone());
        cmd.submitText("col1 integer,\n");
        Thread.sleep(100);
        assertFalse(result.isDone());
        cmd.submitText("col2 varchar(50) default ';'\n");
        Thread.sleep(100);
        assertFalse(result.isDone());
        cmd.submitText(");\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
        assertEquals("create table foo (\ncol1 integer,\ncol2 varchar(50) default ';'\n)",
                result.get().get(0));
    }

    public void testAlterTable() throws Exception
    {
        CommandStuff cmd = new CommandStuff();
        Future<List<String>> result = cmd.openQuery();
        String alter = "alter table foo add column newcol varchar(50)";
        cmd.submitText(alter + ";\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
        assertEquals(alter, result.get().get(0));
    }

    public void testDropTable() throws Exception
    {
        CommandStuff cmd = new CommandStuff();
        Future<List<String>> result = cmd.openQuery();
        String drop = "drop table foo if exists";
        cmd.submitText(drop + ";\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
        assertEquals(drop, result.get().get(0));
    }

    public void testCreateView() throws Exception
    {
        CommandStuff cmd = new CommandStuff();
        Future<List<String>> result = cmd.openQuery();
        String create = "create view foo (col1, col2) as select col1, count(*) from foo group by col1";
        cmd.submitText(create + ";\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
        assertEquals(create, result.get().get(0));

        // From ENG-6641
        result = cmd.openQuery();
        create = "create view foo\n" +
                 "(\n" +
                 "C1\n" +
                 ",C2\n" +
                 ", TOTAL\n" +
                 ")\n" +
                 "as\n" +
                 "select C1\n" +
                 ", C2\n" +
                 ", COUNT(*)\n" +
                 "from bar\n" +
                 "group by C1\n" +
                 ", C2\n";
        cmd.submitText(create + ";\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
    }

    public void testCreateProcedure() throws Exception
    {
        // Check all the DQL/DML possibilities, plus combined subquery select
        CommandStuff cmd = new CommandStuff();
        Future<List<String>> result = cmd.openQuery();
        String create = "create procedure foo as select col1, count(*) from foo group by col1";
        cmd.submitText(create + ";\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
        assertEquals(create, result.get().get(0));

        result = cmd.openQuery();
        create = "create procedure foo as select foo, bar from (select goat, chicken from hats) bats where bats.wings > 1";
        cmd.submitText(create + ";\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
        assertEquals(create, result.get().get(0));

        result = cmd.openQuery();
        create = "create procedure foo as insert into foo values (0, 1)";
        cmd.submitText(create + ";\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
        assertEquals(create, result.get().get(0));

        result = cmd.openQuery();
        create = "create procedure foo as update goats set livestock = 'chicken' where dairy = 'cheese'";
        cmd.submitText(create + ";\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
        assertEquals(create, result.get().get(0));

        result = cmd.openQuery();
        create = "create procedure foo as delete from goats where livestock = 'chicken'";
        cmd.submitText(create + ";\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
        assertEquals(create, result.get().get(0));

        // test with role
        result = cmd.openQuery();
        create = "create procedure foo allow default,adhoc as select foo, bar from (select goat, chicken from hats) bats where bats.wings > 1";
        cmd.submitText(create + ";\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
        assertEquals(create, result.get().get(0));
    }

    public void testSubQuery() throws Exception
    {
        CommandStuff cmd = new CommandStuff();
        Future<List<String>> result = cmd.openQuery();
        String query = "select foo, bar from (select goat, chicken from hats) bats where bats.wings > 1";
        cmd.submitText(query + ";\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
        assertEquals(query, result.get().get(0));
    }

    public void testExec() throws Exception
    {
        CommandStuff cmd = new CommandStuff();
        Future<List<String>> result = cmd.openQuery();
        String exec = "exec selectGoats ';' 'dude' 2";
        cmd.submitText(exec + ";\n");
        cmd.waitOnResult();
        System.out.println("RESULT: " + result.get());
        assertEquals(1, result.get().size());
        assertEquals(exec, result.get().get(0));
    }
}
